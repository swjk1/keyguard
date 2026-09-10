import { redirect } from 'next/navigation';

/**
 * There is no product website here, only the API and the design harness, so the root goes
 * straight to the harness rather than serving a placeholder nobody asked for.
 */
export default function Home() {
  redirect('/preview');
}
