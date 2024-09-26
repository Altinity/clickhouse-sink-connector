#!/bin/bash

# Replace with your GitHub repository (e.g., user/repo)
REPO="altinity/clickhouse-sink-connector"

# Fetch the latest release from GitHub API
latest_release=$(curl -s https://api.github.com/repos/$REPO/releases/latest)

# Also get the date of the latest release
latest_release_date=$(echo $latest_release | jq -r '.published_at')

# Extract the tag name (version) from the JSON response
latest_version=$(echo $latest_release | jq -r '.tag_name')


#echo "****************************************************************************************************"
# Display a message to the usage of the latest_version in color green
echo -e "\e[32mThe latest release is: $latest_version published on: $latest_release_date\e[0m"
# Add new lines.
#echo -e "\n\n"

# echo that the enviroment variable CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE will be
# set to the latest version
#echo "Setting CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE to the latest version"

echo -e "****** Run the following command to set the environment variable pointing to the latest version ******"
echo -e "\n"
echo "****************************************************************************************************"

# Display a message to the usage of the latest_version in color green
echo -e "\e[32m export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=altinity/clickhouse-sink-connector:$latest_version-lt'\e[0m"
echo "****************************************************************************************************"
echo -e "\n"

#export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE='altinity/clickhouse-sink-connector:'$latest_version-lt
export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE='altinity/clickhouse-sink-connector:'$latest_version-lt

#echo run docker-compose up to start sink connector in green
#echo -e "**** Run docker-compose up to start sink connector ***"
#Add stars
#echo "****************************************************************************************************"
