import { useState } from "react";
import {
  pickBackupFolder,
  startServer,
  stopServer,
  probeDiscovery,
  type RuntimeInfo,
  type NetworkInterface,
} from "../platform";
import { displayPath } from "../ui-state";
export type Theme = "system" | "light" | "dark";
export function SettingsView({
  runtime,
  interfaces,
  network,
  setNetwork,
  networkError,
  theme,
  setTheme,
  busy,
  act,
}: {
  runtime: RuntimeInfo | null;
  interfaces: NetworkInterface[];
  network: string;
  setNetwork: (value: string) => void;
  networkError: string;
  theme: Theme;
  setTheme: (value: Theme) => void;
  busy: boolean;
  act: (operation: () => Promise<unknown>) => Promise<void>;
}) {
  const [discoveryResult, setDiscoveryResult] = useState("");
  const ready = runtime !== null;
  const folder = runtime?.folder;
  const listening = runtime?.serverListening ?? false;
  return (
    <div className="settings">
      <section className="settings-section">
        <h2>Хранение</h2>
        <div className="setting-row">
          <div className="grow">
            <h3>Папка для фото и видео</h3>
            <p className="path">{folder ? displayPath(folder.path) : "Не выбрана"}</p>
            <small>
              {listening
                ? "Для смены папки сначала выключите приём."
                : "Уже сохранённые файлы останутся в прежней папке."}
            </small>
          </div>
          <button disabled={!ready || busy || listening} onClick={() => void act(pickBackupFolder)}>
            Выбрать папку
          </button>
        </div>
      </section>
      <section className="settings-section">
        <h2>Приём файлов</h2>
        <div className="setting-row">
          <div className="grow">
            <h3>Локальная сеть</h3>
            <p>Телефон и компьютер должны быть в одной домашней сети.</p>
            <label className="field-label" htmlFor="network">
              Сетевой адаптер
            </label>
            <select
              id="network"
              value={network}
              disabled={!ready || busy || listening}
              onChange={(e) => setNetwork(e.target.value)}
            >
              {!interfaces.length && <option value="">Нет доступной частной сети</option>}
              {interfaces.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.name} · {n.address}
                </option>
              ))}
            </select>
            {networkError && <p className="error-text">{networkError}</p>}
            {runtime?.bindAddress && <small>Адрес: {runtime.bindAddress}</small>}
          </div>
          <button
            disabled={!ready || busy || (!listening && (!folder || !network))}
            onClick={() => void act(() => (listening ? stopServer() : startServer(network)))}
          >
            {listening ? "Выключить приём" : "Включить приём"}
          </button>
        </div>
        <p className="footnote">
          После запуска LanVault приём включается с сохранёнными настройками. Закрытие окна
          оставляет приложение в трее.
        </p>
      </section>
      <section className="settings-section">
        <h2>Внешний вид</h2>
        <div className="setting-row">
          <label htmlFor="theme">Тема</label>
          <select id="theme" value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
            <option value="system">Как в Windows</option>
            <option value="light">Светлая</option>
            <option value="dark">Тёмная</option>
          </select>
        </div>
      </section>
      <section className="settings-section">
        <details>
          <summary>Диагностика</summary>
          <dl>
            <dt>Версия</dt>
            <dd>{runtime?.appVersion ?? "—"}</dd>
            <dt>Протокол</dt>
            <dd>{runtime?.protocolVersion ?? "—"}</dd>
            <dt>Компьютер</dt>
            <dd>{runtime?.hostname ?? "—"}</dd>
            <dt>ID сервера</dt>
            <dd>{runtime?.serverId ?? "—"}</dd>
            <dt>Отпечаток сертификата CA</dt>
            <dd className="fingerprint">{runtime?.fingerprint ?? "—"}</dd>
          </dl>
          <button
            disabled={!listening || busy}
            onClick={() =>
              void act(async () => {
                const result = await probeDiscovery();
                setDiscoveryResult(
                  result.found
                    ? "Обнаружение в локальной сети работает."
                    : "Ответ обнаружения не получен. Проверьте брандмауэр и локальную сеть.",
                );
              })
            }
          >
            Проверить обнаружение
          </button>
          <p role="status">{discoveryResult}</p>
        </details>
      </section>
    </div>
  );
}
