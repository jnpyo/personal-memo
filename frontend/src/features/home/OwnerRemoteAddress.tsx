import {
  isExactOwnerRemoteAppHostname,
  OWNER_REMOTE_APP_HOSTNAME,
} from './homeOverviewModel';

type Props = {
  currentHostname?: string;
  ownerRemoteAppHostname?: string | null;
};

function currentBrowserHostname(): string {
  return typeof window === 'undefined' ? '' : window.location.hostname;
}

export function OwnerRemoteAddress({
  currentHostname = currentBrowserHostname(),
  ownerRemoteAppHostname = OWNER_REMOTE_APP_HOSTNAME,
}: Props) {
  if (!isExactOwnerRemoteAppHostname(currentHostname, ownerRemoteAppHostname)) return null;

  return (
    <p className="settings-remote-address">
      <span>현재 접속 주소</span>
      <strong>{currentHostname}</strong>
    </p>
  );
}
