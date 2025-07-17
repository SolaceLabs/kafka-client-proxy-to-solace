#!/bin/bash

## Create AWS Security Group for Kafka Wireline Proxy Load Balancers
## The same security group can be used for all instances of load balancers for the proxy
## Including bootstrap and instance load balancers

# EKS Cluster Name
CLUSTER_NAME="my-k8s-cluster"

# Get VPC ID from EKS cluster
export VPC_ID=$(aws eks describe-cluster --name $CLUSTER_NAME --query 'cluster.resourcesVpcConfig.vpcId' --output text)

# Create custom security group for Load Balancer
export LB_SG_ID=$(aws ec2 create-security-group \
  --group-name kafka-proxy-lb-sg \
  --description "Kafka Proxy Load Balancer Security Group" \
  --vpc-id $VPC_ID \
  --query 'GroupId' --output text)

echo "Created Security Group: $LB_SG_ID"

# Non-secure health check port -- restrict to internal VPC CIDR
echo "Adding Kafka Proxy Healthcheck Port (8080)..."
aws ec2 authorize-security-group-ingress \
  --group-id $LB_SG_ID \
  --protocol tcp \
  --port 8080 \
  --cidr 172.31.0.0/16

# Secure Kafka Proxy Port -- Open for external access or restrict as needed
echo "Adding SSL Kafka port..."
aws ec2 authorize-security-group-ingress \
  --group-id $LB_SG_ID \
  --protocol tcp \
  --port 9094 \
  --cidr 0.0.0.0/0

# Non-secure port from EKS nodes to Kafka Proxy -- Restrict to internal VPC CIDR
# or eliminate if not needed
echo "Adding non-secure Kafka port..."
aws ec2 authorize-security-group-ingress \
  --group-id $LB_SG_ID \
  --protocol tcp \
  --port 9092 \
  --cidr 172.31.0.0/16

echo "Security Group setup complete. ID: $LB_SG_ID"
