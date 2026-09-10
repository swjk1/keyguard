import type { Metadata, Viewport } from 'next';

import './preview/preview.css';

/**
 * Root layout.
 *
 * The app had no pages at all before this — only route handlers, which do not use layouts — so
 * this exists solely to host the `/preview` design harness. Adding it changes nothing about the
 * API: route handlers are unaffected by layouts, and nothing here runs for them.
 */
export const metadata: Metadata = {
  title: 'Keyguard preview',
  description: 'Design harness for the Keyguard child and parent apps',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  // The harness renders a phone-sized frame; letting the page itself zoom fights that.
  maximumScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
