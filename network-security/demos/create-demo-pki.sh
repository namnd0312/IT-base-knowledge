#!/usr/bin/env bash
# Builds the throwaway PKI used by the TLS demos and the Spring Boot demo, with the JDK's keytool only.
#
#   Demo Root CA ──signs──▶ server  (CN=localhost, serverAuth)
#                └─signs──▶ client  (CN=order-service, clientAuth)
#   attacker / rogue-client: self-signed, NOT signed by the CA
#
# Run from network-security/:   ./demos/create-demo-pki.sh
# Output: out/pki/ (ignored by git). The password is a demo value; these keys protect loopback only.
set -euo pipefail

DIR="out/pki"
PASS="changeit"
rm -rf "$DIR" && mkdir -p "$DIR" && cd "$DIR"
kt() { keytool "$@" -storetype PKCS12 -storepass "$PASS"; }

# 1. Root CA: a self-signed certificate allowed to sign other certificates (BasicConstraints CA:true)
kt -genkeypair -alias ca -keyalg EC -groupname secp256r1 -validity 365 \
   -dname "CN=Demo Root CA,O=Demo" -ext bc:c=ca:true -ext ku:c=keyCertSign,cRLSign -keystore ca.p12
kt -exportcert -alias ca -rfc -file ca.crt -keystore ca.p12

# 2. One leaf identity: key pair -> CSR -> certificate signed by the CA -> chain imported back
issue() {   # issue <alias> <subject DN> <extended key usage> [SAN]
  local alias="$1" dn="$2" eku="$3" san="${4:-}"
  kt -genkeypair -alias "$alias" -keyalg EC -groupname secp256r1 -validity 90 -dname "$dn" -keystore "$alias.p12"
  kt -certreq -alias "$alias" -file "$alias.csr" -keystore "$alias.p12"
  kt -gencert -alias ca -infile "$alias.csr" -outfile "$alias.crt" -rfc -validity 90 \
     -ext ku:c=digitalSignature -ext "eku=$eku" ${san:+-ext "san=$san"} -keystore ca.p12
  kt -importcert -alias ca -file ca.crt -noprompt -keystore "$alias.p12"      # the issuer first...
  kt -importcert -alias "$alias" -file "$alias.crt" -keystore "$alias.p12"    # ...then the signed reply
  kt -delete -alias ca -keystore "$alias.p12"                                 # keep only key + chain
}
issue server "CN=localhost,O=Demo Server" serverAuth "dns:localhost,ip:127.0.0.1"
issue client "CN=order-service,O=Demo Client" clientAuth

# 3. Truststore: only the CA certificate. Whoever holds this file trusts everything the CA signs.
kt -importcert -alias ca -file ca.crt -noprompt -keystore truststore.p12

# 4. Identities the CA never signed (used as the impostor server and the rogue client)
kt -genkeypair -alias attacker -keyalg EC -groupname secp256r1 -validity 90 \
   -dname "CN=localhost,O=Attacker" -ext san=dns:localhost,ip:127.0.0.1 -keystore attacker.p12
kt -genkeypair -alias rogue-client -keyalg EC -groupname secp256r1 -validity 90 \
   -dname "CN=order-service,O=Rogue" -keystore rogue-client.p12

echo "PKI created in $DIR:" && ls
