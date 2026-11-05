"""Isolated real HTTPS regressions: fair quotas, expiry, cancellation and old-client retries."""
import argparse
import importlib.util
import json
import sqlite3
import tempfile
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--exe', type=Path, required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('server_checks', Path(__file__).with_name('test-server.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
passed = []


def ok(name):
    passed.append(name)
    print('PASS ' + name, flush=True)


with tempfile.TemporaryDirectory(prefix='LanVault-lifecycle-') as temporary:
    root = Path(temporary).resolve()
    assert root.parent == Path(tempfile.gettempdir()).resolve() and root.name.startswith('LanVault-lifecycle-')
    archive = root / 'archive'
    archive.mkdir()
    state = root / 'state'
    backend = m.Backend(args.exe.resolve(), state, archive)
    try:
        context, first = m.pair_device(backend, 'Synthetic first phone')
        second_context, second = m.pair_device(backend, 'Synthetic second phone')
        descriptors = [m.descriptor(backend, bytes([i + 1])) for i in range(16)]
        ids = []
        for descriptor in descriptors:
            code, result = m.request(backend, context, 'POST', '/v1/uploads', descriptor, token=first['token'])
            assert code == 200
            ids.append(result['uploadId'])
        code, _ = m.request(backend, context, 'POST', '/v1/uploads', m.descriptor(backend, b'overflow'), token=first['token'])
        assert code == 429
        code, result = m.request(backend, second_context, 'POST', '/v1/uploads', m.descriptor(backend, b'second'), token=second['token'])
        assert code == 200
        other_id = result['uploadId']
        ok('one device cannot monopolize another device queue')
        code, _ = m.request(backend, context, 'DELETE', f'/v1/uploads/{other_id}', token=first['token'])
        assert code == 404
        code, _ = m.request(backend, second_context, 'DELETE', f'/v1/uploads/{other_id}', token=second['token'])
        assert code == 200
        ok('cancellation is authenticated and isolated by device')
        backend.close()
        backend = None
        db = sqlite3.connect(state / 'catalog.sqlite')
        db.execute("UPDATE uploads SET created_at='2000-01-01T00:00:00Z'")
        db.execute('DELETE FROM upload_leases')  # Legacy schema-1 unfinished uploads.
        db.commit()
        db.close()
        backend = m.Backend(args.exe.resolve(), state, None)
        code, _ = m.request(backend, context, 'POST', '/v1/uploads', m.descriptor(backend, b'after expiry'), token=first['token'])
        assert code == 200
        code, revived = m.request(backend, context, 'POST', '/v1/uploads', descriptors[0], token=first['token'])
        assert code == 200 and revived['uploadId'] == ids[0] and revived['state'] == 'created'
        ok('legacy abandoned uploads expire after restart without changing trust or retry IDs')

        data = b'real active upload' * 16384
        descriptor = m.descriptor(backend, data)
        code, result = m.request(backend, context, 'POST', '/v1/uploads', descriptor, token=first['token'])
        assert code == 200
        active_id = result['uploadId']
        connection = m.Connection(backend.hostname, backend.port, context)
        connection.putrequest('PUT', f'/v1/uploads/{active_id}/content')
        connection.putheader('Authorization', 'Bearer ' + first['token'])
        connection.putheader('Content-Length', str(len(data)))
        connection.endheaders()
        connection.send(data[:65536])
        deadline = time.monotonic() + 5
        while not any(u['id'] == active_id for u in backend.admin('status')['activeUploads']):
            if time.monotonic() > deadline:
                raise AssertionError('receiver did not enter active upload')
            time.sleep(.02)
        db = sqlite3.connect(state / 'catalog.sqlite')
        db.execute('UPDATE upload_leases SET touched_at=0 WHERE upload_id=?', (active_id,))
        db.commit()
        db.close()
        code, _ = m.request(backend, second_context, 'POST', '/v1/uploads', m.descriptor(backend, b'concurrent sweep'), token=second['token'])
        assert code == 200
        code, _ = m.request(backend, context, 'DELETE', f'/v1/uploads/{active_id}', token=first['token'])
        assert code == 409  # The active body cannot be removed underneath its writer.
        connection.send(data[65536:])
        response = connection.getresponse()
        assert response.status == 200, response.read()
        response.read()
        connection.close()
        code, result = m.request(backend, context, 'POST', f'/v1/uploads/{active_id}/complete', token=first['token'])
        assert code == 200
        saved = archive / result['receipt']['relativePath']
        assert saved.read_bytes() == data
        ok('expiry and cancellation never remove an active body')
        code, _ = m.request(backend, context, 'DELETE', f'/v1/uploads/{active_id}', token=first['token'])
        assert code == 409 and saved.read_bytes() == data
        ok('committed files cannot be cancelled or deleted')

        failed_data = b'failed staging body'
        descriptor = m.descriptor(backend, failed_data)
        descriptor['sha256'] = '0' * 64
        code, result = m.request(backend, context, 'POST', '/v1/uploads', descriptor, token=first['token'])
        failed_id = result['uploadId']
        code, _ = m.request(backend, context, 'PUT', f'/v1/uploads/{failed_id}/content', token=first['token'], data=failed_data, length=len(failed_data))
        assert code == 422
        staged = archive / '.lanvault' / 'staging' / (failed_id + '.part')
        assert staged.exists()
        code, _ = m.request(backend, context, 'DELETE', f'/v1/uploads/{failed_id}', token=first['token'])
        assert code == 200 and not staged.exists() and saved.read_bytes() == data
        ok('cancel removes only the checked staging file and releases reservation')
    finally:
        if backend:
            backend.close()

print(json.dumps({'passed': len(passed), 'tests': passed}))
