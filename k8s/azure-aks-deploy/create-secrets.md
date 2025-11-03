# Creating Kubernetes Secrets

## Create Solace Credentials Secret

```bash
kubectl create secret generic solace-credentials \
  --from-literal=username="YOUR-SOLACE-USERNAME" \
  --from-literal=password="YOUR-SOLACE-PASSWORD" \
  --namespace=kafka-proxy
```

## Create SSL Certificate Secret (if using SSL)

```bash
kubectl create secret tls kafka-proxy-tls \
  --cert=path/to/tls.crt \
  --key=path/to/tls.key \
  --namespace=kafka-proxy
```

## Verify Secrets

```bash
kubectl get secrets -n kafka-proxy
kubectl describe secret solace-credentials -n kafka-proxy
```
