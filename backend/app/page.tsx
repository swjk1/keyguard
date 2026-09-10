import { notFound, redirect } from 'next/navigation';

/**
 * There is no product website here, only the API and the design harness, so in development
 * the root goes straight to the harness rather than serving a placeholder nobody asked for.
 *
 * In production it 404s. The harness is developer tooling — mock data, no secrets, but also
 * not something to serve to the public, and a deployment whose front page is an internal
 * design tool invites exactly the wrong kind of attention to a child-safety product. The
 * repository's own note said to delete `app/page.tsx` and `app/preview/` before deploying;
 * gating is better than deleting, because a deletion has to be remembered and reversed by
 * hand on every deploy and this cannot be forgotten.
 */
export default function Home() {
  if (process.env.NODE_ENV === 'production') notFound();
  redirect('/preview');
}
