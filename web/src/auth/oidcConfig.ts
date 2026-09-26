import type { AuthProviderProps } from 'react-oidc-context'
import { env } from '../config/env'

// PKCE (S256) is implicit in the authorization_code flow for a public client -
// oidc-client-ts always generates a code_verifier/code_challenge pair and never
// needs a client secret, matching the `revolut-web` Keycloak client.
export const oidcConfig: AuthProviderProps = {
  authority: env.oidcAuthority,
  client_id: env.oidcClientId,
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  onSigninCallback: () => {
    // Strip the ?code=&state= query params Keycloak appends after the redirect
    // so a page refresh doesn't replay a stale authorization code.
    window.history.replaceState({}, document.title, window.location.pathname)
  },
}
