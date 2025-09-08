#!/bin/bash
# file: cleanup-nsg-rules.sh
# The purpose of this script is to clean up Network Security Group (NSG) rules in an Azure AKS cluster.
# *** WARNING ***: Be cautious when running this script, as it will delete NSG rules.
# Important: Update the CLUSTER_NAME and RESOURCE_GROUP variables before running the script.

CLUSTER_NAME="your-cluster-name"
RESOURCE_GROUP="your-resource-group"

NODE_RG=$(az aks show --name $CLUSTER_NAME --resource-group $RESOURCE_GROUP --query nodeResourceGroup -o tsv)
NSG_NAME=$(az network nsg list --resource-group $NODE_RG --query "[0].name" -o tsv)

echo "Cleaning up NSG rules in $NSG_NAME..."

# Delete all kafka-related rules
KAFKA_RULES=$(az network nsg rule list --resource-group $NODE_RG --nsg-name $NSG_NAME \
  --query "[?contains(name, 'kafka') || contains(name, 'health')].name" -o tsv)

for rule in $KAFKA_RULES; do
    echo "Deleting rule: $rule"
    az network nsg rule delete --resource-group $NODE_RG --nsg-name $NSG_NAME --name $rule --yes
done

echo "✅ Cleanup complete!"
