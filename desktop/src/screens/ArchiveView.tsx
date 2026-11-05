import { Icon } from "../Icon";
import { formatBytes, isDesktop, openBackupFolder, setPaused, type RuntimeInfo } from "../platform";
import { archiveStatus, dateLabel, displayPath, archiveLocation } from "../ui-state";
export function ArchiveView({
  runtime,
  busy,
  rate,
  act,
}: {
  runtime: RuntimeInfo | null;
  busy: boolean;
  rate: number;
  act: (operation: () => Promise<unknown>) => Promise<void>;
}) {
  const folder = runtime?.folder;
  const listening = runtime?.serverListening ?? false;
  const devices = runtime?.devices.filter((d) => !d.revoked) ?? [];
  const status = archiveStatus(runtime);
  return (
    <>
      <section className="archive-summary" aria-label="Состояние архива">
        <div className="archive-count">
          <strong>{runtime ? runtime.completedFiles.toLocaleString("ru-RU") : "—"}</strong>
          <span>файлов в архиве</span>
          <span className="muted">{runtime ? formatBytes(runtime.completedBytes) : "—"}</span>
        </div>
        <div className="receiver-state">
          <span className={`badge ${status.tone}`} role="status">
            <span className="status-dot" />
            {isDesktop ? status.label : "Приём недоступен"}
          </span>
          <p>{status.hint}</p>
          {listening && (
            <button
              className="text-button"
              disabled={busy}
              onClick={() => void act(() => setPaused(!runtime?.paused))}
            >
              <Icon name={runtime?.paused ? "play" : "pause"} />
              {runtime?.paused ? "Продолжить приём" : "Приостановить"}
            </button>
          )}
        </div>
      </section>
      <section className="folder-row">
        <span className="folder-icon">
          <Icon name="folder" />
        </span>
        <div>
          <h2>Папка архива</h2>
          <p className="path" title={folder?.path}>
            {folder ? displayPath(folder.path) : "Папка ещё не выбрана"}
          </p>
          {folder && <small>Свободно {formatBytes(folder.availableBytes)}</small>}
        </div>
        {folder && (
          <button disabled={busy} onClick={() => void act(openBackupFolder)}>
            Открыть папку
            <Icon name="arrow" />
          </button>
        )}
      </section>
      {runtime && runtime.activeUploads.length > 0 && (
        <section className="section transfers">
          <div className="section-heading">
            <h2>Сейчас копируется</h2>
            <span className="numeric">
              {rate > 0 ? `${formatBytes(String(Math.round(rate)))}/с` : "Измеряем скорость…"}
            </span>
          </div>
          <div className="transfer-list">
            {runtime.activeUploads.map((u) => (
              <div className="transfer-row" key={u.id}>
                <div>
                  <span className="filename">{u.name ?? "Приём файла"}</span>
                  <small>
                    {formatBytes(u.receivedBytes)}
                    {u.size != null ? ` из ${formatBytes(String(u.size))}` : ""}
                  </small>
                </div>
                <progress
                  aria-label={`Копирование ${u.name ?? "файла"}`}
                  max={Number(u.size) || 1}
                  value={Number(u.receivedBytes)}
                />
              </div>
            ))}
          </div>
        </section>
      )}
      <section className="section">
        <div className="section-heading">
          <h2>Последние файлы</h2>
          <span className="muted">
            {runtime?.history.length ? `Последние ${runtime.history.length}` : ""}
          </span>
        </div>
        {!runtime?.history.length ? (
          <div className="empty-state">
            <Icon name="archive" />
            <h3>{folder ? "Здесь появятся сохранённые файлы" : "Начните с папки для копий"}</h3>
            <p>
              {folder
                ? devices.length
                  ? "Откройте LanVault на телефоне и нажмите «Скопировать сейчас»."
                  : "Подключите телефон, чтобы отправлять фото и видео на этот компьютер."
                : "Выберите папку на локальном диске, затем подключите телефон по QR-коду."}
            </p>
          </div>
        ) : (
          <div className="file-table" role="table" aria-label="Последние сохранённые файлы">
            <div className="file-table-head" role="row">
              <span role="columnheader">Файл</span>
              <span role="columnheader">Размер</span>
              <span role="columnheader">Сохранён</span>
            </div>
            {runtime.history.map((file) => (
              <div className="file-table-row" role="row" key={file.id}>
                <div role="cell" className="file-name">
                  <span className={`file-indicator ${file.healthy ? "ready" : "warning"}`}>
                    <Icon name={file.healthy ? "check" : "archive"} />
                  </span>
                  <div>
                    <span className="filename" title={file.name}>
                      {file.name}
                    </span>
                    <small title={file.path}>
                      {file.healthy ? archiveLocation(file.path) : "Нужна повторная отправка"}
                    </small>
                  </div>
                </div>
                <span role="cell" className="numeric muted">
                  {formatBytes(file.size)}
                </span>
                <time role="cell" className="muted">
                  {dateLabel(file.committedAt)}
                </time>
              </div>
            ))}
          </div>
        )}
      </section>
    </>
  );
}
