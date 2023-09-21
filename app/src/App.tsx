import { useMemo } from 'react';
import { AuthProvider, AuthProviderProps, useAuth } from "react-oidc-context";
import uuid4 from 'uuid4';
import './App.css';

const defaultHostRealmIdp = ["http://localhost:8080", "master1"] as const
const myUri = location.origin + "/"

function getHostAndRealm(mode: Exclude<AuthProviderProps['response_mode'], undefined>): readonly [string, string] {
  const oidcParams = mode == "fragment"
    ? new URLSearchParams(window.location.hash ? window.location.hash.substring(1) : "")
    : new URLSearchParams(window.location.search ? window.location.search.substring(0) : "")

  const kcInstance = oidcParams?.get("kc_instance")
  if (!kcInstance) return defaultHostRealmIdp

  const state = oidcParams!.get("state")
  const [host, realm] = atob(kcInstance).split("=") as [string, string]

  const req = JSON.parse(localStorage.getItem(`oidc.${state}`) || "{}")
  if (req) {
    req.authority = `${host}/realms/${realm}`
    const redirectUri = new URL(req.redirect_uri as string)
    if (mode === "fragment") {
      if (!redirectUri.hash.includes(`kc_instance=${kcInstance}`)) {
        if (redirectUri.hash) {
          redirectUri.hash += `&kc_instance=${kcInstance}`
        } else {
          redirectUri.hash = `kc_instance=${kcInstance}`
        }
      }
    } else {
      redirectUri.searchParams.append("kc_instance", kcInstance)
    }
    req.redirect_uri = redirectUri.toString()
    localStorage.setItem(`oidc.${state}`, JSON.stringify(req))
  }

  return [host, realm]
}

function Inner() {
  const auth = useAuth()
  const nonce = useMemo(() => uuid4(), [])

  if (auth.isAuthenticated) {
    return (
      <div className='card'>
        <p>
          Welcome {auth.user?.profile.email}!
        </p>
        <p>
          You are signed in via {auth.user?.profile.iss}.
        </p>
        <button onClick={() => auth.signoutRedirect({ post_logout_redirect_uri: myUri })}>
          Sign Out
        </button>

      </div>
    )
  }

  return (
    <div className='card'>
      <button onClick={() => auth.signinRedirect({ redirect_uri: myUri, nonce })}>Sign In</button>

      <p>
        Sign in with either <code>foo@bar.com</code> or <code>foo@foo.com</code> and the password <code>foobar</code>.
      </p>
    </div>
  )
}


function App() {
  const responseMode = "fragment"
  const [host, realm] = getHostAndRealm(responseMode)

  const oidcConfig: AuthProviderProps = {
    authority: `${host}/realms/${realm}`,
    client_id: "app",
    redirect_uri: myUri,
    response_mode: responseMode,
    automaticSilentRenew: true,
    onSigninCallback: () => {
      window.history.replaceState({}, document.title, window.location.pathname)
    }
  }

  return (
    <AuthProvider {...oidcConfig}>
      <Inner />
    </AuthProvider>
  )
}

export default App

