#!/bin/bash
# file: create-aks-network-rules.sh
## Use this script as part of a quickstart to establish network rules for AKS

## Create Azure NSG rules for Kafka Wireline Proxy Load Balancers

# AKS Cluster Name
CLUSTER_NAME="kafka-proxy-test"
RESOURCE_GROUP="kafka-proxy-test"

echo "Finding NSG for AKS cluster: $CLUSTER_NAME"

# Get the node resource group (where network resources are created)
NODE_RG=$(az aks show --name $CLUSTER_NAME --resource-group $RESOURCE_GROUP --query nodeResourceGroup -o tsv)
echo "Node Resource Group: $NODE_RG"

# FIXED: Find the NSG automatically
NSG_INFO=$(az network nsg list --resource-group $NODE_RG --query "[0].{name:name, resourceGroup:'$NODE_RG'}" -o json 2>/dev/null)

if [ "$NSG_INFO" = "null" ] || [ -z "$NSG_INFO" ]; then
    echo "No NSG found in node resource group, checking main resource group..."
    NSG_INFO=$(az network nsg list --resource-group $RESOURCE_GROUP --query "[0].{name:name, resourceGroup:'$RESOURCE_GROUP'}" -o json 2>/dev/null)
fi

if [ "$NSG_INFO" = "null" ] || [ -z "$NSG_INFO" ]; then
    echo "❌ ERROR: No NSG found!"
    echo "Available NSGs in node resource group ($NODE_RG):"
    az network nsg list --resource-group $NODE_RG --query "[].name" -o table
    echo "Available NSGs in main resource group ($RESOURCE_GROUP):"
    az network nsg list --resource-group $RESOURCE_GROUP --query "[].name" -o table
    exit 1
fi

NSG_NAME=$(echo $NSG_INFO | jq -r '.name')
NSG_RESOURCE_GROUP=$(echo $NSG_INFO | jq -r '.resourceGroup')

echo "✅ Found NSG: $NSG_NAME in resource group: $NSG_RESOURCE_GROUP"

# Get VNet CIDR range
echo "Getting VNet CIDR range..."
VNET_INFO=$(az network vnet list --resource-group $NODE_RG --query "[0]" 2>/dev/null)
if [ "$VNET_INFO" = "null" ] || [ -z "$VNET_INFO" ]; then
    VNET_INFO=$(az network vnet list --resource-group $RESOURCE_GROUP --query "[0]")
fi

VNET_CIDR=$(echo $VNET_INFO | jq -r '.addressSpace.addressPrefixes[0]')
echo "VNet CIDR: $VNET_CIDR"

if [ "$VNET_CIDR" = "null" ] || [ -z "$VNET_CIDR" ]; then
    echo "Warning: Could not detect VNet CIDR, using default 10.0.0.0/16"
    VNET_CIDR="10.0.0.0/16"
fi

echo ""
echo "Creating NSG rules for Kafka Wireline Proxy..."

# Create NSG rule for Kafka SSL port - External access
echo "Adding Kafka SSL port (9094) for external access..."
az network nsg rule create \
  --resource-group $NSG_RESOURCE_GROUP \
  --nsg-name $NSG_NAME \
  --name allow-kafka-ssl-external \
  --protocol tcp \
  --priority 1001 \
  --destination-port-range 9094 \
  --access allow \
  --direction inbound \
  --source-address-prefixes "*" \
  --description "Allow Kafka SSL port for external clients"

# Create NSG rule for health checks - Azure Load Balancer only
echo "Adding health check port (8080) for Azure Load Balancer..."
az network nsg rule create \
  --resource-group $NSG_RESOURCE_GROUP \
  --nsg-name $NSG_NAME \
  --name allow-health-check-azure-lb \
  --protocol tcp \
  --priority 1002 \
  --destination-port-range 8080 \
  --access allow \
  --direction inbound \
  --source-address-prefixes "AzureLoadBalancer" \
  --description "Allow Azure Load Balancer health checks"

# Create NSG rule for health checks - Internal VNet access only
echo "Adding health check port (8080) for internal VNet access..."
az network nsg rule create \
  --resource-group $NSG_RESOURCE_GROUP \
  --nsg-name $NSG_NAME \
  --name allow-health-check-internal \
  --protocol tcp \
  --priority 1003 \
  --destination-port-range 8080 \
  --access allow \
  --direction inbound \
  --source-address-prefixes "$VNET_CIDR" \
  --description "Allow health check port for internal VNet access"

# Create NSG rule for non-secure Kafka port - Internal VNet only
echo "Adding non-secure Kafka port (9092) for internal access..."
az network nsg rule create \
  --resource-group $NSG_RESOURCE_GROUP \
  --nsg-name $NSG_NAME \
  --name allow-kafka-plaintext-internal \
  --protocol tcp \
  --priority 1004 \
  --destination-port-range 9092 \
  --access allow \
  --direction inbound \
  --source-address-prefixes "$VNET_CIDR" \
  --description "Allow non-secure Kafka port for internal VNet access only"

echo ""
echo "✅ NSG rules created successfully!"
echo "NSG: $NSG_NAME (Resource Group: $NSG_RESOURCE_GROUP)"
echo "VNet CIDR used: $VNET_CIDR"
echo ""
echo "Created rules:"
echo "- Port 9094 (Kafka SSL): External access allowed"
echo "- Port 8080 (Health): Azure Load Balancer + Internal VNet only"
echo "- Port 9092 (Kafka Plain): Internal VNet only"
echo ""
echo "To verify the rules:"
echo "az network nsg rule list --resource-group $NSG_RESOURCE_GROUP --nsg-name $NSG_NAME --query \"[?contains(name, 'allow-kafka') || contains(name, 'allow-health')].{Name:name, Priority:priority, Access:access, Protocol:protocol, DestinationPortRange:destinationPortRange, SourceAddressPrefix:sourceAddressPrefix}\" --output table"
