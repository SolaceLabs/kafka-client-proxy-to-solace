# Create Secrets for Proxy Deployment to Kubernetes

## Kafka Keystore

```bash
kubectl create secret generic kafka-keystore \
  --from-file=keystore=/path/to/keystore.pkcs12 \
  -n kproxy
```

### Kafka Keystore Password Secret

To create a secret for the keystore password and access it as an environment variable in your deployment:

```bash
kubectl create secret generic kafka-keystore-password \
  --from-literal=KAFKA_KEYSTORE_PASSWORD=your_keystore_password \
  -n kproxy
```

