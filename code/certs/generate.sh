#!/bin/bash
set -e

cert_dir=.
. ../../.env

openssl genpkey -algorithm RSA -out "$cert_dir/own.key" -pkeyopt rsa_keygen_bits:2048
openssl req -new -x509 -key "$cert_dir/own.key" -out "$cert_dir/own.crt" -days 365 -subj "/CN=$OWN_GATE_ID"
openssl pkcs12 -export -out "$cert_dir/own.p12" -inkey "$cert_dir/own.key" -in "$cert_dir/own.crt" -name "$OWN_GATE_ID" -passout pass:changeit
