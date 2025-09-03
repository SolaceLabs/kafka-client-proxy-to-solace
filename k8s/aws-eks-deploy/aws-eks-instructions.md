# AWS EKS Deployment Instructions

## Prerequisites

- AWS CLI configured with appropriate permissions
- kubectl installed
- jq installed for JSON parsing
- EKS cluster already created

## Initial Setup

```bash
# Update kubeconfig to connect to your EKS cluster
aws eks update-kubeconfig --region us-east-2 --name kproxy-cluster

# Verify connection
kubectl get nodes

# Navigate to deployment directory
cd k8s/aws-eks-deploy
```

## Create Security Groups

```bash
# Create AWS security groups for Load Balancer and Worker Nodes
./create-aws-security-groups.sh

# Create Kubernetes namespace
kubectl create namespace kafka-proxy
```

## Create Secrets

```bash
# Create keystore secret from PKCS12 file
kubectl create secret generic kafka-keystore \
  --from-file=keystore=/path/to/certs/keystore.pkcs12 \
  -n kafka-proxy

# Create keystore password secret
kubectl create secret generic kafka-keystore-password \
  --from-literal=KAFKA_KEYSTORE_PASSWORD=password \
  -n kafka-proxy
```

## Create Load Balancers

```bash
# Deploy instance-specific load balancers
kubectl apply -f instance-lb.yaml

# Deploy bootstrap load balancer
kubectl apply -f bootstrap-lb.yaml

# Wait for load balancers to be ready (may take 2-3 minutes)
echo "Waiting for load balancers to be provisioned..."
kubectl get svc -n kafka-proxy -w

# Extract external hostnames from instance load balancers
export EXTERNAL_LB_HOST_LIST=$(kubectl get svc -n kafka-proxy \
  --selector='!service.kubernetes.io/load-balancer-cleanup' \
  -o json | \
  jq -r '.items[] | select(.metadata.name | contains("instance-lb")) | .status.loadBalancer.ingress[0].hostname' | \
  paste -sd "," -)

echo "External LB Host List: $EXTERNAL_LB_HOST_LIST"

# Verify hostnames are available
if [ -z "$EXTERNAL_LB_HOST_LIST" ]; then
  echo "ERROR: No instance load balancer hostnames found. Check load balancer status."
  kubectl get svc -n kafka-proxy
  exit 1
fi
```

## Deploy Application

```bash
# Deploy ConfigMap
kubectl apply -f proxy-config-map.yaml

# Deploy StatefulSet with load balancer hostnames
sed "s/REPLACE_WITH_ACTUAL_LB_HOST_LIST/$EXTERNAL_LB_HOST_LIST/g" proxy-sts.yaml | kubectl apply -f -

# Wait for pods to be ready
echo "Waiting for pods to be ready..."
kubectl wait --for=condition=ready pod -l app=kafka-wireline-proxy -n kafka-proxy --timeout=300s
```

## Verify Deployment

```bash
# Check pod status
kubectl get pods -n kafka-proxy -o wide

# Check service endpoints
kubectl get svc -n kafka-proxy

# Check load balancer hostnames
kubectl get svc -n kafka-proxy -o custom-columns=NAME:.metadata.name,EXTERNAL-IP:.status.loadBalancer.ingress[0].hostname

# Test health endpoints
BOOTSTRAP_LB=$(kubectl get svc kafka-proxy-bootstrap-lb -n kafka-proxy -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
curl -f http://$BOOTSTRAP_LB:8080/health

# Check application logs
kubectl logs -f kafka-wireline-proxy-0 -n kafka-proxy
kubectl logs -f kafka-wireline-proxy-1 -n kafka-proxy
```

## Test Connectivity

```bash
# Get bootstrap load balancer hostname
BOOTSTRAP_LB=$(kubectl get svc kafka-proxy-bootstrap-lb -n kafka-proxy -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

echo "Bootstrap Load Balancer: $BOOTSTRAP_LB"

# Test SSL connectivity
echo "Testing SSL connection..."
openssl s_client -connect $BOOTSTRAP_LB:9094 -servername $BOOTSTRAP_LB </dev/null

# Test plaintext connectivity (should work from VPC only)
echo "Testing plaintext connection..."
nc -zv $BOOTSTRAP_LB 9092

# Test with Kafka client (replace with your test topic)
# kafka-console-producer.sh --bootstrap-server $BOOTSTRAP_LB:9094 --topic PRODUCER_TOPIC:test-topic --producer.config ssl.properties
```

## Troubleshooting

```bash
# Check pod events
kubectl describe pods -n kafka-proxy

# Check service events
kubectl describe svc -n kafka-proxy

# Check security group rules
aws ec2 describe-security-groups --group-names kafka-proxy-lb-sg kafka-proxy-worker-sg

# Check load balancer target groups
aws elbv2 describe-load-balancers --query 'LoadBalancers[?contains(LoadBalancerName, `kafka`)]'

# Port forward for local testing
kubectl port-forward kafka-wireline-proxy-0 9092:9092 9094:9094 8080:8080 -n kafka-proxy
```

## Cleanup

```bash
# Delete StatefulSet and services
kubectl delete -f proxy-sts.yaml -n kafka-proxy
kubectl delete -f instance-lb.yaml -n kafka-proxy
kubectl delete -f bootstrap-lb.yaml -n kafka-proxy
kubectl delete -f proxy-config-map.yaml -n kafka-proxy

# Delete secrets
kubectl delete secret kafka-keystore kafka-keystore-password -n kafka-proxy

# Delete namespace
kubectl delete namespace kafka-proxy

# Delete security groups (optional)
aws ec2 delete-security-group --group-name kafka-proxy-lb-sg
aws ec2 delete-security-group --group-name kafka-proxy-worker-sg
```

## Configuration Files

Ensure you have the following files in the aws-deploy directory:
- `create-aws-security-groups.sh` - Security group creation script
- `instance-lb.yaml` - Instance-specific load balancers
- `bootstrap-lb.yaml` - Bootstrap load balancer  
- `proxy-config-map.yaml` - Application configuration
- `proxy-sts.yaml` - StatefulSet with `REPLACE_WITH_ACTUAL_LB_HOST_LIST` placeholder

## Notes

- Load balancers may take 2-3 minutes to provision
- SSL port (9094) are open to internet
- Plaintext port (9092) are restricted to VPC
- Health check port (8080) is available for load balancer health checks
- Each pod gets its own instance-specific load balancer for direct access
