#!/bin/bash
# Test external connectivity to AKS Kafka Proxy
# Important: This script assumes a TWO NODE kafka-proxy cluster
# Important: Update the CLUSTER_NAME, RESOURCE_GROUP, and NAMESPACE variables before running the script.
# Important: Update the Bootstrap and Instance Service names to reflect those used in your deployment

CLUSTER_NAME="kafka-proxy-test"
RESOURCE_GROUP="kafka-proxy-test"
NAMESPACE="kafka-proxy"

echo "🔍 Testing AKS Kafka Proxy External Connectivity..."

# 1. Check Load Balancer Services
echo ""
echo "1️⃣ Checking Load Balancer Services..."
kubectl get svc -n $NAMESPACE -o wide

# 2. Get External IPs
echo ""
echo "2️⃣ Getting External IPs..."
BOOTSTRAP_IP=$(kubectl get svc kafka-wireline-proxy-bootstrap-lb -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
INSTANCE_0_IP=$(kubectl get svc kafka-wireline-proxy-instance-lb-0 -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
INSTANCE_1_IP=$(kubectl get svc kafka-wireline-proxy-instance-lb-1 -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)

echo "Bootstrap LB IP: $BOOTSTRAP_IP"
echo "Instance 0 LB IP: $INSTANCE_0_IP"
echo "Instance 1 LB IP: $INSTANCE_1_IP"

if [ -z "$BOOTSTRAP_IP" ] || [ "$BOOTSTRAP_IP" = "<pending>" ]; then
    echo "❌ ERROR: Load balancer IPs not assigned yet!"
    echo "Wait a few minutes and try again."
    exit 1
fi

# 3. Test Basic Connectivity
echo ""
echo "3️⃣ Testing Basic Connectivity..."

test_connection() {
    local ip=$1
    local port=$2
    local description=$3
    
    echo -n "Testing $description ($ip:$port)... "
    if timeout 10 bash -c "</dev/tcp/$ip/$port" 2>/dev/null; then
        echo "✅ CONNECTED"
        return 0
    else
        echo "❌ FAILED"
        return 1
    fi
}

test_connection $BOOTSTRAP_IP 9094 "Bootstrap SSL"
test_connection $INSTANCE_0_IP 9094 "Instance 0 SSL"
test_connection $INSTANCE_1_IP 9094 "Instance 1 SSL"

# 4. Test SSL Handshake
echo ""
echo "4️⃣ Testing SSL Handshake..."
echo "Testing SSL handshake with $BOOTSTRAP_IP:9094..."
timeout 10 openssl s_client -connect $BOOTSTRAP_IP:9094 -servername $BOOTSTRAP_IP <<< "Q" 2>/dev/null | grep -E "(CONNECTED|SSL handshake|Verify return code)"

# 5. Check NSG Rules
echo ""
echo "5️⃣ Checking NSG Rules..."
NODE_RG=$(az aks show --name $CLUSTER_NAME --resource-group $RESOURCE_GROUP --query nodeResourceGroup -o tsv)
NSG_NAME=$(az network nsg list --resource-group $NODE_RG --query "[0].name" -o tsv)

if [ -n "$NSG_NAME" ]; then
    echo "NSG: $NSG_NAME"
    echo "Kafka SSL rules:"
    az network nsg rule list --resource-group $NODE_RG --nsg-name $NSG_NAME \
      --query "[?destinationPortRange=='9094'].{Name:name, Priority:priority, Access:access, SourceAddressPrefix:sourceAddressPrefix}" \
      --output table
else
    echo "❌ No NSG found!"
fi

# 6. Check Pod Status
echo ""
echo "6️⃣ Checking Pod Status..."
kubectl get pods -n $NAMESPACE -o wide

# 7. Check Load Balancer Health
echo ""
echo "7️⃣ Checking Load Balancer Health..."
for ip in $BOOTSTRAP_IP $INSTANCE_0_IP $INSTANCE_1_IP; do
    if [ -n "$ip" ]; then
        echo -n "Health check $ip:8080... "
        if curl -s --connect-timeout 10 http://$ip:8080/health >/dev/null 2>&1; then
            echo "✅ HEALTHY"
        else
            echo "❌ UNHEALTHY (expected - health port should be internal only)"
        fi
    fi
done

# 8. Kafka Client Test
echo ""
echo "8️⃣ Testing with Kafka Client..."
echo "Testing Kafka producer connection..."

cat > /tmp/kafka-test.properties << EOF
bootstrap.servers=$BOOTSTRAP_IP:9094
security.protocol=SSL
ssl.truststore.location=/tmp/truststore.jks
ssl.truststore.password=changeit
ssl.endpoint.identification.algorithm=
EOF

echo "Kafka config created at /tmp/kafka-test.properties"
echo "To test manually:"
echo "kafka-console-producer.sh --bootstrap-server $BOOTSTRAP_IP:9094 --topic test-topic --producer.config /tmp/kafka-test.properties"

echo ""
echo "🎯 External Connectivity Test Complete!"
echo ""
echo "External IPs to use for clients:"
echo "  Bootstrap: $BOOTSTRAP_IP:9094"
echo "  Instance 0: $INSTANCE_0_IP:9094"
echo "  Instance 1: $INSTANCE_1_IP:9094"
