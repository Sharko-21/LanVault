import { useEffect, useRef, useState } from "react";
import {
  approvePairing,
  createPairing,
  cancelPairing,
  errorMessage,
  getInterfaces,
  getRuntimeInfo,
  isDesktop,
  pickBackupFolder,
  revokeDevice,
  startServer,
  type Device,
  type NetworkInterface,
  type Pairing,
  type RuntimeInfo,
} from "./platform";
import { archiveStatus, sampleRate, type Sample } from "./ui-state";

import { Icon } from "./Icon";
import { ArchiveView } from "./screens/ArchiveView";
import { DevicesView } from "./screens/DevicesView";
import { SettingsView, type Theme } from "./screens/SettingsView";
type Tab = "archive" | "devices" | "settings";
function readTheme(): Theme {
  try {
    const v = localStorage.getItem("lanvault.theme");
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

export function App() {
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [tab, setTab] = useState<Tab>("archive");
  const [runtime, setRuntime] = useState<RuntimeInfo | null>(null);
  const [interfaces, setInterfaces] = useState<NetworkInterface[]>([]);
  const [network, setNetwork] = useState("");
  const [networkError, setNetworkError] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [revoking, setRevoking] = useState<Device | null>(null);
  const [now, setNow] = useState(Date.now());
  const [rate, setRate] = useState(0);
  const samples = useRef<Sample[]>([]);
  const dialog = useRef<HTMLDialogElement>(null);
  const revokeDialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("lanvault.theme", theme);
    } catch {
      /* Optional preference. */
    }
  }, [theme]);
  useEffect(() => {
    if (!isDesktop) return;
    let active = true;
    let polling = false;
    const poll = async () => {
      if (polling) return;
      polling = true;
      try {
        const value = await getRuntimeInfo();
        if (active) {
          setRuntime(value);
          setError((old) => (old === errorMessage("INITIALIZING") ? "" : old));
          const next = sampleRate(samples.current, value.receivedBytesTotal, Date.now());
          samples.current = next.samples;
          setRate(next.rate);
        }
      } catch (reason) {
        if (active) setError(errorMessage(reason));
      } finally {
        polling = false;
      }
    };
    void poll();
    const timer = setInterval(() => void poll(), 2000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, []);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const ready = runtime !== null;
  useEffect(() => {
    if (!isDesktop || !ready) return;
    let active = true;
    const load = async () => {
      try {
        const values = await getInterfaces();
        if (active) {
          setInterfaces(values);
          setNetwork((current) =>
            values.some((v) => v.id === current)
              ? current
              : (values.find((v) => runtime?.bindAddress?.startsWith(v.address + ":"))?.id ??
                values[0]?.id ??
                ""),
          );
          setNetworkError("");
        }
      } catch (reason) {
        if (active) setNetworkError(errorMessage(reason));
      }
    };
    void load();
    const timer = setInterval(() => void load(), 15000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [ready]);
  useEffect(() => {
    if (pairing && !dialog.current?.open) dialog.current?.showModal();
  }, [pairing]);
  useEffect(() => {
    if (revoking) revokeDialog.current?.showModal();
  }, [revoking]);
  async function act(operation: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await operation();
      setRuntime(await getRuntimeInfo());
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }
  const folder = runtime?.folder;
  const listening = runtime?.serverListening ?? false;
  const pending = runtime?.pendingPairing;
  const devices = runtime?.devices.filter((d) => !d.revoked) ?? [];
  const status = archiveStatus(runtime);
  const expired = !!pairing && pairing.expiresAt * 1000 <= now;
  const connect = () => void act(async () => setPairing(await createPairing()));
  const cancel = () => {
    dialog.current?.close();
  };
  const primary = !folder ? (
    <button
      className="primary"
      disabled={!ready || busy}
      onClick={() => void act(pickBackupFolder)}
    >
      <Icon name="folder" />
      Выбрать папку
    </button>
  ) : !listening ? (
    <button
      className="primary"
      disabled={!network || busy}
      onClick={() => void act(() => startServer(network))}
    >
      <Icon name="play" />
      Включить приём
    </button>
  ) : (
    <button className="primary" disabled={busy} onClick={connect}>
      <Icon name="devices" />
      Подключить телефон
    </button>
  );

  return (
    <div className="shell">
      <aside className="sidebar">
        <a className="brand" href="#main">
          <span className="brand-mark">
            <Icon name="archive" />
          </span>
          LanVault
        </a>
        <nav aria-label="Разделы">
          {(
            [
              ["archive", "Архив"],
              ["devices", "Устройства"],
              ["settings", "Настройки"],
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              aria-current={tab === id ? "page" : undefined}
              onClick={() => setTab(id)}
            >
              <Icon name={id} />
              {label}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className={`status-dot ${status.tone}`} />
          <span>{isDesktop ? status.label : "Предпросмотр"}</span>
          <small>LanVault · {runtime?.appVersion ?? "Windows"}</small>
        </div>
      </aside>
      <main id="main" tabIndex={-1}>
        {!isDesktop && (
          <div className="notice">
            Предпросмотр интерфейса. Для приёма файлов откройте приложение LanVault на Windows.
          </div>
        )}
        {error && (
          <div className="notice error" role="alert">
            <span>{error}</span>
            <button
              aria-label="Закрыть сообщение"
              className="icon-button"
              onClick={() => setError("")}
            >
              <Icon name="close" />
            </button>
          </div>
        )}
        {runtime?.lastError && (
          <div className="notice error" role="alert">
            {errorMessage(runtime.lastError)}
          </div>
        )}
        <header className="page-header">
          <div>
            <h1>{tab === "archive" ? "Архив" : tab === "devices" ? "Устройства" : "Настройки"}</h1>
            <p>
              {tab === "archive"
                ? "Фото и видео с ваших телефонов"
                : tab === "devices"
                  ? "Телефоны с доступом к этому компьютеру"
                  : "Хранение, сеть и внешний вид"}
            </p>
          </div>
          {tab !== "settings" && primary}
        </header>
        {tab === "archive" && <ArchiveView runtime={runtime} busy={busy} rate={rate} act={act} />}
        {tab === "devices" && <DevicesView devices={devices} busy={busy} onRevoke={setRevoking} />}
        {tab === "settings" && (
          <SettingsView
            runtime={runtime}
            interfaces={interfaces}
            network={network}
            setNetwork={setNetwork}
            networkError={networkError}
            theme={theme}
            setTheme={setTheme}
            busy={busy}
            act={act}
          />
        )}
      </main>
      <dialog
        ref={dialog}
        className="pairing-dialog"
        aria-labelledby="pairing-title"
        onClose={() => {
          setPairing(null);
          void act(cancelPairing);
        }}
      >
        <div className="dialog-heading">
          <h2 id="pairing-title">Подключить телефон</h2>
          <button className="icon-button" aria-label="Закрыть" onClick={cancel}>
            <Icon name="close" />
          </button>
        </div>
        {pending ? (
          <>
            <p>
              Сравните код на телефоне <strong>{pending.name}</strong> и на этом экране.
            </p>
            <div className="verification-code">{pending.verificationCode}</div>
            <p className="muted">Подтвердите, только если коды совпадают.</p>
            <div className="dialog-actions">
              <button
                disabled={busy}
                onClick={() =>
                  void act(async () => {
                    await approvePairing(pending.id, false);
                    cancel();
                  })
                }
              >
                Отклонить
              </button>
              <button
                className="primary"
                disabled={busy}
                onClick={() =>
                  void act(async () => {
                    await approvePairing(pending.id, true);
                    cancel();
                  })
                }
              >
                Коды совпадают
              </button>
            </div>
          </>
        ) : (
          <>
            <p>
              В LanVault на телефоне откройте «Копирование», нажмите «Сканировать QR-код» и наведите
              камеру на код.
            </p>
            {pairing && !expired ? (
              <div className="qr-frame">
                <img
                  src={pairing.qrDataUrl}
                  width="320"
                  height="320"
                  alt="QR-код подключения к этому компьютеру"
                />
              </div>
            ) : (
              <div className="qr-expired">Срок действия кода истёк</div>
            )}
            <div className="qr-caption">
              <span role="status">
                {expired
                  ? "Создайте новый код для подключения."
                  : `Код действует ещё ${Math.max(0, Math.ceil(((pairing?.expiresAt ?? 0) * 1000 - now) / 1000))} с`}
              </span>
              <button className="text-button" disabled={busy} onClick={connect}>
                Обновить код
              </button>
            </div>
            <p className="footnote">
              Телефон и компьютер должны быть в одной локальной сети. После сканирования нужно
              сравнить коды.
            </p>
          </>
        )}
      </dialog>
      <dialog ref={revokeDialog} aria-labelledby="revoke-title" onClose={() => setRevoking(null)}>
        <h2 id="revoke-title">Отключить {revoking?.name}?</h2>
        <p>
          Телефон больше не сможет отправлять файлы. Копии на компьютере сохранятся. Для нового
          подключения понадобится QR-код.
        </p>
        <div className="dialog-actions">
          <button onClick={() => revokeDialog.current?.close()}>Отмена</button>
          <button
            className="danger-button"
            disabled={busy}
            onClick={() =>
              void act(async () => {
                if (revoking) await revokeDevice(revoking.id);
                revokeDialog.current?.close();
              })
            }
          >
            Отключить
          </button>
        </div>
      </dialog>
    </div>
  );
}
