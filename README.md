# keycloak-host-and-realm-chooser

An MVP on how to shard keycloak for massive horizontal parralelization.

This relies on a few pieces working together:

1. An mechanism to resolve an `HostRealmIdP` object from a username.
   In this example, an environment variable is parsed into a static map.

2. An custom authorization flow which handles the username _before_ the
   password is entered. This is realized with the custom `UserForm`.

3. An app that can handle dynamic keycloak backend configuration. This is realized
   by sneaking an `kc_instance` parameter into the `redirect_uri` and having that
   parsed by the FE app.


To run it:

```
$ ./gradlew start
$ ....
$ cd ./app && npm install && npm run dev
```

Open http://localhost:5173
* log in with `foo@foo.com` `foobar`. This will log you in with the _default_ `kc0` (on port 8080)
* log in with `foo@bar.com` `foobar`: This will log you in on kc in a different `kc1` (on port 8081)
* log in with `foo@baz.com`: This will take you to the realm `idp-client` on `kc0`, and because
  theres an `idp_hint`, it'll take you straight to the realm `idp`, where you log in with the pw
  `foobar` and are then routed back to `idp-client` and finally back to the app
