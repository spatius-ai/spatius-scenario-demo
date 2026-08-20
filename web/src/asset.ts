/**
 * The URL of a file in `public/`.
 *
 * Everything is served under a path of its own (see `base` in vite.config.ts), so a
 * leading-slash literal like `/avatar-kian.jpg` resolves to the site root and 404s. Vite
 * rewrites relative URLs it can see at build time, but not strings assembled in code, and
 * not the ones inside a `<style>` block — so both go through here.
 */
export function asset(name: string): string {
  return `${import.meta.env.BASE_URL}${name}`
}
