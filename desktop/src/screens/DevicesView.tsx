import { Icon } from "../Icon";
import { type Device } from "../platform";
import { dateLabel } from "../ui-state";
export function DevicesView({
  devices,
  busy,
  onRevoke,
}: {
  devices: Device[];
  busy: boolean;
  onRevoke: (device: Device) => void;
}) {
  return (
    <section className="section device-list">
      {devices.length ? (
        devices.map((device) => (
          <div className="device-row" key={device.id}>
            <span className="device-icon">
              <Icon name="devices" />
            </span>
            <div className="grow">
              <h2>{device.name}</h2>
              <p>Последняя связь: {dateLabel(device.lastSeen)}</p>
            </div>
            <button className="text-button danger" disabled={busy} onClick={() => onRevoke(device)}>
              Отключить
            </button>
          </div>
        ))
      ) : (
        <div className="empty-state">
          <Icon name="devices" />
          <h3>Подключите первый телефон</h3>
          <p>
            Откройте LanVault на телефоне и отсканируйте QR-код. Сохранённое подключение будет
            работать автоматически.
          </p>
        </div>
      )}
      <p className="footnote">
        Время последней связи обновляется с задержкой до минуты. Отключение устройства закроет ему
        доступ; сохранённые файлы останутся.
      </p>
    </section>
  );
}
