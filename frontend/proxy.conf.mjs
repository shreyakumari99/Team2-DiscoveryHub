/**
 * Angular dev-server proxy config. Maps /api/<service>/* to the right backend
 * port so the frontend can call relative /api/... URLs in both dev and docker.
 *
 * Wired into angular.json serve options (proxyConfig). Run: `ng serve`.
 *
 * In docker, the equivalent routing is done by nginx (see Dockerfile).
 */

/**
 * A disposition run deletes synchronously, so its request stays open for as
 * long as the deletion takes — well past any default proxy timeout on a large
 * corpus. Allow half an hour, matching the nginx side. The retention screen
 * copes with a timeout either way, but only if the timeout is a clean 504
 * rather than a silently hung socket.
 */
const LONG_RUNNING_MS = 30 * 60 * 1000;

export const PROXY_CONFIG = [
  {
    context: ['/api/v1/cases', '/api/v1/custodians'],
    target: 'http://localhost:8084',
    changeOrigin: true,
  },
  {
    context: ['/api/v1/search', '/api/v1/saved-searches'],
    target: 'http://localhost:8083',
    changeOrigin: true,
  },
  {
    context: ['/api/v1/retention'],
    target: 'http://localhost:8085',
    changeOrigin: true,
    timeout: LONG_RUNNING_MS,
    proxyTimeout: LONG_RUNNING_MS,
  },
  {
    context: ['/api/v1/holds'],
    target: 'http://localhost:8085',
    changeOrigin: true,
  },
  {
    context: ['/api/v1/exports'],
    target: 'http://localhost:8086',
    changeOrigin: true,
  },
  {
    context: ['/api/v1/audit'],
    target: 'http://localhost:8087',
    changeOrigin: true,
  },
  {
    context: ['/api/v1/messages'],
    target: 'http://localhost:8082',
    changeOrigin: true,
  },
  // ingestion-service serves the same /api/v1/messages path as archive-service,
  // so it cannot be routed by prefix alone. The frontend addresses it as
  // /api/v1/ingestion and the prefix is rewritten back on the way out; nginx
  // does the same thing in docker (see frontend/Dockerfile).
  {
    context: ['/api/v1/ingestion'],
    target: 'http://localhost:8081',
    changeOrigin: true,
    pathRewrite: { '^/api/v1/ingestion': '/api/v1/messages' },
  },
];

export default PROXY_CONFIG;
