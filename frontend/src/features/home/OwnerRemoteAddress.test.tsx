import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { OwnerRemoteAddress } from './OwnerRemoteAddress';

describe('OwnerRemoteAddress', () => {
  it('renders only the exact configured owner hostname', () => {
    const markup = renderToStaticMarkup(
      <OwnerRemoteAddress
        currentHostname="memo.example.com"
        ownerRemoteAppHostname="memo.example.com"
      />,
    );

    expect(markup).toContain('현재 접속 주소');
    expect(markup).toContain('<strong>memo.example.com</strong>');

    const caseInsensitiveBrowserMarkup = renderToStaticMarkup(
      <OwnerRemoteAddress
        currentHostname="MEMO.EXAMPLE.COM"
        ownerRemoteAppHostname="memo.example.com"
      />,
    );
    expect(caseInsensitiveBrowserMarkup).toContain('<strong>MEMO.EXAMPLE.COM</strong>');
  });

  it('does not render for any configured hostname rejected by the deployment boundary', () => {
    for (const ownerRemoteAppHostname of [
      'memo.localhost',
      '127.0.0.1',
      '::1',
      'calendar.example.com',
      'example.com',
      'memo.dev.example.com',
      'MEMO.EXAMPLE.COM',
      'memo.example.com.',
      ' memo.example.com',
      'memo.example.com ',
      'memo.example.com.evil.test',
    ]) {
      expect(renderToStaticMarkup(
        <OwnerRemoteAddress
          currentHostname="memo.example.com"
          ownerRemoteAppHostname={ownerRemoteAppHostname}
        />,
      )).toBe('');
    }

    expect(renderToStaticMarkup(
      <OwnerRemoteAddress currentHostname="memo.example.com" ownerRemoteAppHostname={null} />,
    )).toBe('');
  });

  it('does not render when the current browser hostname differs or has an evil suffix', () => {
    for (const currentHostname of [
      'calendar.example.com',
      'memo.example.com.evil.test',
      'memo.example.com.',
      ' memo.example.com',
    ]) {
      expect(renderToStaticMarkup(
        <OwnerRemoteAddress
          currentHostname={currentHostname}
          ownerRemoteAppHostname="memo.example.com"
        />,
      )).toBe('');
    }
  });
});
