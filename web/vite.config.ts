import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { avatarkitVitePlugin } from '@spatius/avatarkit/vite'

export default defineConfig({
  // Served under a path of its own rather than at the root, named after the repository so
  // it matches what the native clients report as their app name.
  //
  // The SDK reports the page's location with every session, and on the web that is all
  // there is to identify the app by — there is no bundle id or package name the way there
  // is on Android and iOS. Left at `/`, this demo is indistinguishable in the data from
  // any other project someone happens to be running on localhost, which is most of them.
  base: '/spatius-scenario-demo/',
  // The rendering core is WASM, and this plugin serves it and its workers with the right
  // response headers. Without it, a request for a .wasm falls through to the SPA fallback
  // and gets index.html back, which reports "expected magic word 00 61 73 6d, found 3c 21
  // 64 6f" — those four bytes being `<!do`.
  plugins: [vue(), avatarkitVitePlugin()],
  resolve: {
    // If the main SDK and the RTC package each resolve their own copy of avatarkit, the
    // two AvatarView types are distinct and cannot be passed between them. The subpath
    // needs its own entry: '@spatius/avatarkit' does not cover its /internal-telemetry,
    // which would resolve a second module instance whose provider the main SDK never
    // initialized, so spans opened through it are silently dropped.
    dedupe: ['@spatius/avatarkit', '@spatius/avatarkit/internal-telemetry'],
  },
  server: {
    port: 5180,
    strictPort: true,
  },
})
