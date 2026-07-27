# Android release signing certificate

The private upload key is stored only in the `qelg/Android` GitHub Actions secrets.

- Alias: `harness-android`
- Algorithm: 4096-bit RSA / SHA384withRSA
- Valid through: 2053-12-12
- SHA-256 fingerprint: `D0:70:1E:6A:B0:D1:17:69:DD:34:D0:AE:2A:B0:B3:9D:71:70:8D:06:92:FC:D7:AB:86:38:5B:86:32:E4:93:E8`

The public certificate is committed as `upload-certificate.pem`. Never commit the `.jks` keystore or `key.properties`.
