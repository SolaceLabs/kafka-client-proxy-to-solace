# AKS Network Rules and Connectivity Testing - QUICK START

This QUICK START guide provides step-by-step instructions for creating Azure NSG rules and testing connectivity for the Kafka Wireline Proxy deployed to AKS.

## Prerequisites

- Azure CLI installed and authenticated
- kubectl configured for your AKS cluster
- Proper permissions to modify NSG rules

## 1. Create NSG Rules

First, create the necessary Network Security Group rules to allow external traffic to the Kafka proxy:

```bash
# Make the script executable
chmod +x create-aks-network-rules.sh

# Run the script to create NSG rules
./create-aks-network-rules.sh
```

## 2. Verify Load Balancer Services

Check if external IPs are assigned to your load balancer services:

```bash
# Check if external IPs are assigned
kubectl get svc -n kafka-proxy
```

Expected output should show external IPs assigned:
```
NAME                                TYPE           CLUSTER-IP    EXTERNAL-IP   PORT(S)
kafka-wireline-proxy-bootstrap-lb   LoadBalancer   10.0.123.45   20.1.2.3      9094:30123/TCP
```

## 3. Test External Connectivity

### Basic TCP Connectivity Tests

```bash
# Get external IP
EXTERNAL_IP=$(kubectl get svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

echo "Testing connectivity to: $EXTERNAL_IP:9094"

# Test TCP connection with telnet
telnet $EXTERNAL_IP 9094

# Test with netcat (if available)
nc -zv $EXTERNAL_IP 9094

# Test with timeout using bash
timeout 10 bash -c "</dev/tcp/$EXTERNAL_IP/9094" && echo "Connected" || echo "Failed"
```

### SSL Handshake Test

```bash
# Test SSL handshake
openssl s_client -connect $EXTERNAL_IP:9094 -servername $EXTERNAL_IP
```

## 4. Check Azure Load Balancer Configuration

### List Load Balancers

```bash
# Check load balancer in Azure
NODE_RG=$(az aks show --name kafka-proxy-test --resource-group kafka-proxy-test --query nodeResourceGroup -o tsv)
az network lb list --resource-group $NODE_RG --output table
```

### Check Load Balancer Rules

```bash
# Get load balancer name and check rules
LB_NAME=$(az network lb list --resource-group $NODE_RG --query "[0].name" -o tsv)
az network lb rule list --resource-group $NODE_RG --lb-name $LB_NAME --output table
```

### Check Health Probes

```bash
# Check health probe configuration
az network lb probe list --resource-group $NODE_RG --lb-name $LB_NAME --output table
```

## 5. Debug Pod Issues

### Check Pod Status and Logs

```bash
# Check pod logs
kubectl logs -n kafka-proxy kafka-wireline-proxy-0

# Follow logs in real-time
kubectl logs -n kafka-proxy kafka-wireline-proxy-0 -f

# Check pod status and details
kubectl describe pod kafka-wireline-proxy-0 -n kafka-proxy

# Get pod status overview
kubectl get pods -n kafka-proxy -o wide
```

### Check Service Endpoints

```bash
# Check service endpoints
kubectl get endpoints -n kafka-proxy

# Describe the load balancer service
kubectl describe svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy
```

### Check Recent Events

```bash
# Check recent events in the namespace
kubectl get events -n kafka-proxy --sort-by='.lastTimestamp'

# Check cluster-wide events
kubectl get events --all-namespaces --sort-by='.lastTimestamp' | tail -20
```

## 6. Test Internal Connectivity

### Create Debug Pod

```bash
# Create test pod for internal testing
kubectl run debug-pod --image=alpine/curl -it --rm -- sh
```

### Test Internal Connectivity (from inside debug pod)

```bash
# Test health endpoint
curl http://kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local:8080/health

# Test Kafka port connectivity
telnet kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local 9094

# Test with netcat
nc -zv kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local 9094

# Exit the debug pod
exit
```

## 7. Advanced Troubleshooting

### Check Pod Port Bindings

```bash
# Check what ports the application is listening on
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- netstat -tlnp

# Alternative if netstat is not available
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- ss -tlnp
```

### Check NSG Rules

```bash
# Check NSG rules for Kafka ports
NSG_NAME=$(az network nsg list --resource-group $NODE_RG --query "[0].name" -o tsv)
az network nsg rule list --resource-group $NODE_RG --nsg-name $NSG_NAME \
  --query "[?destinationPortRange=='9094'].{Name:name, Priority:priority, Access:access, SourceAddressPrefix:sourceAddressPrefix}" \
  --output table
```

### Test Pod Health Internally

```bash
# Test health endpoint from within the pod
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- curl -s localhost:8080/health
```

## 8. Network Connectivity Matrix

| Test | Command | Expected Result |
|------|---------|----------------|
| Pod Health | `kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- curl localhost:8080/health` | `{"status":"UP"}` |
| Internal Service | `curl http://kafka-wireline-proxy-headless.kafka-proxy.svc.cluster.local:8080/health` | `{"status":"UP"}` |
| External TCP | `nc -zv $EXTERNAL_IP 9094` | `Connection succeeded` |
| External SSL | `openssl s_client -connect $EXTERNAL_IP:9094` | SSL handshake success |

## Troubleshooting Common Issues

### Issue: External IP is `<pending>`
```bash
# Check load balancer service events
kubectl describe svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy

# Check Azure subscription limits
az vm list-usage --location eastus --output table
```

### Issue: Connection timeout
```bash
# Check if NSG rules are applied
az network nsg rule list --resource-group $NODE_RG --nsg-name $NSG_NAME --output table

# Check pod status
kubectl get pods -n kafka-proxy -o wide
```

### Issue: SSL handshake failure
```bash
# Check SSL configuration in pod
kubectl exec -n kafka-proxy kafka-wireline-proxy-0 -- ls -la /app/keystore
kubectl logs -n kafka-proxy kafka-wireline-proxy-0 | grep -i ssl
```

## Cleanup Commands

```bash
# Delete debug pod if it's stuck
kubectl delete pod debug-pod --force --grace-period=0

# Restart pods if needed
kubectl rollout restart statefulset/kafka-wireline-proxy -n kafka-proxy

# Delete and recreate load balancer service
kubectl delete svc kafka-wireline-proxy-bootstrap-lb -n kafka-proxy
kubectl apply -f bootstrap
```

