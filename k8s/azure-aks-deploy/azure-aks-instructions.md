# Azure AKS Deployment Instructions for Kafka Wireline Proxy

This document provides comprehensive instructions for deploying the Kafka Wireline Proxy to Azure Kubernetes Service (AKS).

## Prerequisites

- Azure CLI installed and authenticated
- kubectl installed
- Docker installed (for building custom images if needed)
- Azure subscription with sufficient permissions
<<<<<<< HEAD
=======
- Helm for advanced/production deployment options
>>>>>>> origin/beta-fixes-and-enhancements-1

## 1. Azure Setup
Skip this section if Azure CLI is already set up. You should ensure that you are logged into the CLI using commands shown below.

### Install and Configure Azure CLI

```bash
# Install Azure CLI (macOS)
brew install azure-cli

# Install Azure CLI (Ubuntu/Debian)
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Login to Azure
az login

# Set default subscription (if you have multiple)
az account set --subscription "your-subscription-id"

# Verify login
az account show
```

### Register Required Resource Providers


```bash
# Register required providers
az provider register --namespace Microsoft.ContainerService
az provider register --namespace Microsoft.Network
az provider register --namespace Microsoft.Storage

# Verify registration status
az provider show --namespace Microsoft.ContainerService --query "registrationState"
```

## 2. Create AKS Cluster
Skip this section if using an existing EKS cluster

### Create Resource Group

```bash
# Set variables
RESOURCE_GROUP="kafka-proxy-test"
LOCATION="eastus"
CLUSTER_NAME="kafka-proxy-test"

# Create resource group
az group create --name $RESOURCE_GROUP --location $LOCATION
```

### Create AKS Cluster

```bash
# Create AKS cluster with recommended settings for Kafka workloads
az aks create \
  --resource-group $RESOURCE_GROUP \
  --name $CLUSTER_NAME \
  --node-count 3 \
  --node-vm-size Standard_D4s_v3 \
  --kubernetes-version 1.28.0 \
  --network-plugin azure \
  --network-policy calico \
  --load-balancer-sku standard \
  --enable-managed-identity \
  --enable-cluster-autoscaler \
  --min-count 1 \
  --max-count 5 \
  --zones 1 2 3 \
  --generate-ssh-keys \
  --tags environment=test project=kafka-wireline-proxy

# Get AKS credentials
az aks get-credentials --resource-group $RESOURCE_GROUP --name $CLUSTER_NAME

# Verify cluster connection
kubectl get nodes
```

### Configure Node Pool (Optional - for production)

```bash
# Add dedicated node pool for Kafka workloads
az aks nodepool add \
  --resource-group $RESOURCE_GROUP \
  --cluster-name $CLUSTER_NAME \
  --name kafkapool \
  --node-count 2 \
  --node-vm-size Standard_D8s_v3 \
  --node-taints workload=kafka:NoSchedule \
  --labels workload=kafka \
  --zones 1 2 3
```

## 3. Prepare Certificates and Secrets

### Create SSL Certificates

```bash
# Create directory for certificates
mkdir -p certs

# Generate self-signed certificate for testing
openssl req -new -x509 -keyout certs/server.key -out certs/server.crt -days 365 -nodes \
  -subj "/C=US/ST=CA/L=San Francisco/O=Test/CN=kafka-proxy"

# Create PKCS12 keystore
openssl pkcs12 -export -in certs/server.crt -inkey certs/server.key \
  -out certs/keystore.pkcs12 -name server -password pass:serverpass

# Verify keystore
keytool -list -keystore certs/keystore.pkcs12 -storetype PKCS12 -storepass serverpass
```

#### Add certificate to a truststore to use with your Kafka clients

After creating the server certificate, you need to create a truststore and add the certificate so Kafka clients can trust the proxy's SSL connection.

```bash
# Extract the certificate from the PKCS12 keystore
keytool -exportcert -alias server -keystore certs/keystore.pkcs12 -storetype PKCS12 \
  -storepass serverpass -file certs/server.crt

# Create a JKS truststore and import the server certificate
keytool -import -alias kafka-proxy-server -file certs/server.crt \
  -keystore certs/truststore.jks -storepass changeit -noreply

# Alternative: Create PKCS12 truststore (more modern format)
keytool -import -alias kafka-proxy-server -file certs/server.crt \
  -keystore certs/truststore.p12 -storetype PKCS12 -storepass changeit -noreply

# Verify the certificate was added to truststore
keytool -list -keystore certs/truststore.jks -storepass changeit

# Check certificate details in truststore
keytool -list -v -keystore certs/truststore.jks -storepass changeit -alias kafka-proxy-server
```

Create a client configuration file that references the truststore:

```bash
# Create Kafka client properties file
cat > certs/kafka-client.properties << EOF
bootstrap.servers=EXTERNAL_IP:9094
security.protocol=SSL
ssl.truststore.location=certs/truststore.jks
ssl.truststore.password=changeit
ssl.truststore.type=JKS
ssl.endpoint.identification.algorithm=
ssl.check.hostname=false
EOF

# Alternative client config using PKCS12 truststore
cat > certs/kafka-client-p12.properties << EOF
bootstrap.servers=EXTERNAL_IP:9094
security.protocol=SSL
ssl.truststore.location=certs/truststore.p12
ssl.truststore.password=changeit
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=
ssl.check.hostname=false
EOF
```

For production environments, you may want to use CA-signed certificates:

```bash
# If using a CA-signed certificate, import the CA certificate instead
# Download your CA certificate (example: ca-cert.pem)
keytool -import -alias ca-root -file ca-cert.pem \
  -keystore certs/truststore.jks -storepass changeit -noreply

# For intermediate CAs, import the full certificate chain
keytool -import -alias ca-intermediate -file intermediate-ca.pem \
  -keystore certs/truststore.jks -storepass changeit -
```

### Create Kubernetes Namespace and Secrets

```bash
# Create namespace
kubectl create namespace kafka-proxy

# Create keystore secret
kubectl create secret generic kafka-keystore \
  --from-file=keystore=certs/keystore.pkcs12 \
  -n kafka-proxy

# Create keystore password secret
kubectl create secret generic kafka-keystore-password \
  --from-literal=KAFKA_KEYSTORE_PASSWORD=serverpass \
  -n kafka-proxy

# Verify secrets
kubectl get secrets -n kafka-proxy
```

## 4. Network Configuration

### Create NSG Rules

```bash
# Navigate to deployment directory
cd k8s/__dev-aks-deploy

# Make NSG script executable and run
chmod +x create-aks-network-rules.sh
./create-aks-network-rules.sh
```

### Verify Network Rules

```bash
# Get node resource group
NODE_RG=$(az aks show --name $CLUSTER_NAME --resource-group $RESOURCE_GROUP --query nodeResourceGroup -o tsv)

# List NSG rules
NSG_NAME=$(az network nsg list --resource-group $NODE_RG --query "[0].name" -o tsv)
az network nsg rule list --resource-group $NODE_RG --nsg-name $NSG_NAME --output table
```

## 5. Deploy Application

### Apply Configuration

```bash
# Apply ConfigMap
kubectl apply -f proxy-config-map.yaml

# Apply Load Balancer services
kubectl apply -f bootstrap-lb.yaml
kubectl apply -f instance-lb.yaml

# Wait for external IPs to be assigned
echo "Waiting for external IPs..."
kubectl get svc -n kafka-proxy -w
```

### Deploy StatefulSet

```bash
# Get external load balancer IPs
export EXTERNAL_LB_HOST_LIST=$(kubectl get svc -n kafka-proxy \
  -o json | \
  jq -r '.items[] | select(.metadata.name | contains("instance-lb")) | .status.loadBalancer.ingress[0].ip + ":9094"' | \
  paste -sd "," -)

echo "External LB Host List: $EXTERNAL_LB_HOST_LIST"

# Apply StatefulSet with substituted values
sed "s/REPLACE_WITH_ACTUAL_LB_HOST_LIST/$EXTERNAL_LB_HOST_LIST/g" proxy-sts.yaml | kubectl apply -f -
```

### Monitor Deployment

```bash
# Watch pod creation
kubectl get pods -n kafka-proxy -w

# Check pod logs
kubectl logs -n kafka-proxy kafka-wireline-proxy-0 -f

# Check service status
kubectl get svc -n kafka-proxy
```

## 6. Testing and Validation

### Internal Health Checks

```bash
# Test pod health endpoint
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- curl localhost:8080/health

# Check pod port bindings
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- netstat -tlnp
```

### External Connectivity Tests

```bash
# Get external IP
EXTERNAL_IP=$(kubectl get svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

# Test TCP connectivity
timeout 10 bash -c "</dev/tcp/$EXTERNAL_IP/9094" && echo "Connected" || echo "Failed"

# Test SSL handshake
openssl s_client -connect $EXTERNAL_IP:9094 -servername $EXTERNAL_IP
```

### Kafka Client Testing

```bash
# Create Kafka client configuration
cat > kafka-client.properties << EOF
bootstrap.servers=$EXTERNAL_IP:9094
security.protocol=SSL
ssl.truststore.location=certs/truststore.jks
ssl.truststore.password=changeit
ssl.endpoint.identification.algorithm=
EOF

# Test with Kafka console producer (requires Kafka tools)
kafka-console-producer.sh --bootstrap-server $EXTERNAL_IP:9094 \
  --topic test-topic \
  --producer.config kafka-client.properties
```

## 7. Monitoring and Observability

### Enable Azure Monitor for Containers

```bash
# Enable monitoring addon
az aks enable-addons \
  --resource-group $RESOURCE_GROUP \
  --name $CLUSTER_NAME \
  --addons monitoring
```

### Deploy Prometheus and Grafana (Optional)

```bash
# Add Helm repositories
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

# Install Prometheus
helm install prometheus prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace

# Get Grafana admin password
kubectl get secret --namespace monitoring prometheus-grafana \
  -o jsonpath="{.data.admin-password}" | base64 --decode
```

## 8. Production Considerations

### Security Hardening

```bash
# Enable Azure Policy for Kubernetes
az aks enable-addons \
  --resource-group $RESOURCE_GROUP \
  --name $CLUSTER_NAME \
  --addons azure-policy

# Create Pod Security Policy
kubectl apply -f - <<EOF
apiVersion: policy/v1beta1
kind: PodSecurityPolicy
metadata:
  name: kafka-proxy-psp
spec:
  privileged: false
  allowPrivilegeEscalation: false
  requiredDropCapabilities:
    - ALL
  volumes:
    - 'configMap'
    - 'emptyDir'
    - 'projected'
    - 'secret'
    - 'downwardAPI'
    - 'persistentVolumeClaim'
  runAsUser:
    rule: 'MustRunAsNonRoot'
  seLinux:
    rule: 'RunAsAny'
  fsGroup:
    rule: 'RunAsAny'
EOF
```

### Backup and Disaster Recovery

```bash
# Install Velero for backup
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts/
helm install velero vmware-tanzu/velero \
  --namespace velero \
  --create-namespace \
  --set configuration.provider=azure \
  --set configuration.backupStorageLocation.bucket=your-backup-container \
  --set configuration.backupStorageLocation.config.resourceGroup=$RESOURCE_GROUP \
  --set configuration.backupStorageLocation.config.storageAccount=your-storage-account
```

### Resource Limits and Quotas

```bash
# Create resource quota
kubectl apply -f - <<EOF
apiVersion: v1
kind: ResourceQuota
metadata:
  name: kafka-proxy-quota
  namespace: kafka-proxy
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi
    persistentvolumeclaims: "10"
EOF
```

## 9. Troubleshooting

### Common Issues

```bash
# Check cluster health
kubectl get componentstatuses

# Check node status
kubectl describe nodes

# Check pod events
kubectl get events -n kafka-proxy --sort-by='.lastTimestamp'

# Check load balancer configuration
kubectl describe svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy
```

### Debug Network Issues

```bash
# Create debug pod
kubectl run debug-pod --image=nicolaka/netshoot -it --rm -- bash

# Test internal connectivity
nslookup kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local
telnet kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local 9094
```

### Collect Logs

```bash
# Collect all logs
kubectl logs -n kafka-proxy --all-containers=true --prefix=true > kafka-proxy-logs.txt

# Get cluster info
kubectl cluster-info dump > cluster-info.txt
```

## 10. Cleanup

### Delete Application

```bash
# Delete StatefulSet and services
kubectl delete -f proxy-sts.yaml
kubectl delete -f bootstrap-lb.yaml
kubectl delete -f instance-lb.yaml
kubectl delete -f proxy-config-map.yaml

# Delete secrets
kubectl delete secret kafka-keystore kafka-keystore-password -n kafka-proxy

# Delete namespace
kubectl delete namespace kafka-proxy
```

### Delete AKS Cluster

```bash
# Delete the entire cluster
az aks delete --resource-group $RESOURCE_GROUP --name $CLUSTER_NAME --yes

# Delete resource group (if no longer needed)
az group delete --name $RESOURCE_GROUP --yes
```
