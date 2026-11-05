import { invoke, isTauri } from "@tauri-apps/api/core";
export interface FolderInfo {
  datasetId: string;
  path: string;
  availableBytes: string;
  totalBytes: string;
}
export interface Device {
  id: string;
  name: string;
  revoked: boolean;
  lastSeen: string | null;
}
export interface RuntimeInfo {
  appVersion: string;
  protocolVersion: number;
  platform: string;
  serverListening: boolean;
  serverId: string;
  hostname: string;
  fingerprint: string;
  bindAddress: string | null;
  paused: boolean;
  folder: FolderInfo | null;
  devices: Device[];
  pendingPairing: { id: string; name: string; verificationCode: string; expiresAt: number } | null;
  history: {
    id: string;
    name: string;
    size: string;
    path: string;
    committedAt: string;
    healthy: boolean;
  }[];
  completedFiles: number;
  completedBytes: string;
  receivedBytesTotal: string;
  activeUploads: {
    id: string;
    receivedBytes: string;
    name: string | null;
    size: string | number | null;
  }[];
  lastError: string | null;
}
export interface NetworkInterface {
  id: string;
  name: string;
  address: string;
  prefix: number;
}
export interface Pairing {
  qrDataUrl: string;
  expiresAt: number;
  spkiSha256: string;
}
export const isDesktop = isTauri();
export const getRuntimeInfo = () => invoke<RuntimeInfo>("runtime_info");
export const pickBackupFolder = () => invoke<FolderInfo | null>("pick_backup_folder");
export const openBackupFolder = () => invoke("open_backup_folder");
export const getInterfaces = () => invoke<NetworkInterface[]>("network_interfaces");
export const startServer = (interfaceId: string) => invoke("start_server", { interfaceId });
export const stopServer = () => invoke("stop_server");
export const setPaused = (paused: boolean) => invoke("set_paused", { paused });
export const cancelPairing = () => invoke("cancel_pairing");
export const createPairing = () => invoke<Pairing>("create_pairing");
export const probeDiscovery = () => invoke<{ found: boolean }>("probe_discovery");
export const approvePairing = (requestId: string, approve: boolean) =>
  invoke("approve_pairing", { requestId, approve });
export const revokeDevice = (deviceId: string) => invoke("revoke_device", { deviceId });
export function formatBytes(bytes: string): string {
  const value = BigInt(bytes);
  for (const [unit, power] of [
    ["ТиБ", 4n],
    ["ГиБ", 3n],
    ["МиБ", 2n],
    ["КиБ", 1n],
  ] as const) {
    const divisor = 1024n ** power;
    if (value >= divisor)
      return `${value / divisor},${((value % divisor) * 10n) / divisor} ${unit}`;
  }
  return `${value} Б`;
}
export function errorMessage(error: unknown): string {
  const code = String(error);
  if (code.startsWith("STATE_INIT:"))
    return "Не удалось открыть состояние приложения. Закройте другие экземпляры и проверьте доступ к локальному каталогу AppData. В управляемой среде используйте отладочный каталог из инструкции запуска.";
  const known: Record<string, string> = {
    INITIALIZING: "Подготавливаем архив и проверяем журнал сохранения…",
    STOP_SERVER_FIRST: "Остановите приём перед сменой папки.",
    PRIVATE_PHYSICAL_NETWORK_REQUIRED:
      "Нужна физическая сеть с профилем Windows «Частная» и частным IPv4.",
    NETWORK_POLICY_UNAVAILABLE:
      "Windows не предоставила сведения о безопасности сети. Приём закрыт.",
    NETWORK_CHANGED:
      "Профиль или адрес сети изменился. Приём остановлен; выберите доступную частную сеть.",
    ROOT_UNAVAILABLE:
      "Папка архива недоступна или была заменена. Подключите диск и выберите её повторно.",
    ROOT_NOT_CONFIGURED: "Сначала выберите папку архива.",
    FOLDER_OPEN_FAILED: "Не удалось открыть папку. Проверьте, подключён ли диск.",
    RECOVERY_NEEDS_ATTENTION:
      "Некоторые файлы не прошли восстановление. Проверьте диск; повторная отправка восстановит копию.",
    "storage operation failed":
      "Папка недоступна. Нужен обычный локальный NTFS-каталог вне OneDrive, без ссылок и точек перенаправления.",
    "database operation failed":
      "Не удалось открыть или сохранить каталог. Проверьте доступность локального диска.",
    "request expired": "Срок сопряжения истёк. Создайте новый QR-код.",
    "not enough free space":
      "Недостаточно свободного места. Освободите место и повторите отправку.",
  };
  return known[code] ?? "Операция не завершена. Обновите состояние и повторите действие.";
}
