#!/usr/bin/env bash
# Generates a throwaway CA, the broker keystore and the clients' truststore for compose.secure.yaml
# (ADR 0018). Development only: the CA lives 30 days and its key stays on this machine.
#
# Output goes to .kafka-certs/ (untracked). Only .kafka-certs/secrets/ is mounted into containers; the
# CA key stays one level up. Needs keytool (any JDK). Run it again to replace everything.
#
# Environment (dev defaults):
#   KAFKA_CERTS_DIR         output directory, default .kafka-certs
#   KAFKA_STORE_PASSWORD    password of every keystore and truststore, default ratekit-dev-store
#   KAFKA_ADMIN_PASSWORD    SCRAM password of the admin user, written to client.properties, default ratekit-dev-admin
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="${KAFKA_CERTS_DIR:-$root/.kafka-certs}"
pass="${KAFKA_STORE_PASSWORD:-ratekit-dev-store}"
admin_pass="${KAFKA_ADMIN_PASSWORD:-ratekit-dev-admin}"
keytool_bin="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
san="san=dns:kafka,dns:localhost,ip:127.0.0.1"

command -v "$keytool_bin" >/dev/null || { echo "keytool not found: install a JDK or set JAVA_HOME" >&2; exit 1; }

rm -rf "$dir"
mkdir -p "$dir/secrets"
umask 077
cd "$dir"

# 1. the CA: a key pair that may sign certificates
"$keytool_bin" -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=ratekit-dev-ca" -ext bc:c -ext ku:c=keyCertSign,cRLSign \
  -keystore ca.p12 -storetype PKCS12 -storepass "$pass" -keypass "$pass"
"$keytool_bin" -exportcert -rfc -alias ca -keystore ca.p12 -storepass "$pass" -file secrets/ca.crt

# 2. the broker key pair, signed by the CA; the names must match what clients connect to. The broker is
#    also a client of its own INTERNAL listener, and Kafka checks its keystore both ways: hence clientAuth.
"$keytool_bin" -genkeypair -alias broker -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=kafka" -ext "$san" \
  -keystore secrets/broker.p12 -storetype PKCS12 -storepass "$pass" -keypass "$pass"
"$keytool_bin" -certreq -alias broker -keystore secrets/broker.p12 -storepass "$pass" -file broker.csr
"$keytool_bin" -gencert -alias ca -keystore ca.p12 -storepass "$pass" -infile broker.csr -outfile broker.crt -rfc \
  -validity 30 -ext "$san" -ext eku=serverAuth,clientAuth
"$keytool_bin" -importcert -noprompt -alias ca -file secrets/ca.crt -keystore secrets/broker.p12 -storepass "$pass"
"$keytool_bin" -importcert -noprompt -alias broker -file broker.crt -keystore secrets/broker.p12 -storepass "$pass"
rm -f broker.csr broker.crt

# 3. the clients' truststore: only the CA
"$keytool_bin" -importcert -noprompt -alias ca -file secrets/ca.crt \
  -keystore secrets/truststore.p12 -storetype PKCS12 -storepass "$pass"

# 4. admin client settings for the Kafka CLI tools (health check, ACL setup, console commands)
cat > secrets/client.properties <<PROPS
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="$admin_pass";
ssl.truststore.location=/etc/kafka/secrets/truststore.p12
ssl.truststore.type=PKCS12
ssl.truststore.password=$pass
PROPS

# the containers run as other users than the owner of these throwaway files
chmod 755 "$dir/secrets"
chmod 644 "$dir"/secrets/*

echo "Wrote $dir/secrets: broker.p12, truststore.p12, ca.crt, client.properties"
echo "Next: podman compose -f compose.yaml -f compose.secure.yaml up -d --build"
