#!/bin/bash

# Function to fetch the latest tag from Docker Hub
get_latest_tag() {
    # Docker Hub repository name in the format "username/repository"
    REPO="$1"
    
    # Fetch the tags using Docker Hub API and parse them using jq
    # Sort the tags in descending order and pick the first one
    LATEST_TAG=$(curl -s "https://hub.docker.com/v2/repositories/$REPO/tags/?page_size=100" \
    | jq -r '.results[].name' \
    | sort -V \
    #| grep -v -e 'latest' -e 'rc' -e '-alpha' -e '-beta' \
    | tail -n 1)

    echo "The latest tag for $REPO is: $LATEST_TAG"
}

# Check if the repository name is provided as an argument
if [ -z "$1" ]; then
    echo "Usage: $0 <repository>"
    echo "Example: $0 library/nginx"
    exit 1
fi

# Call the function with the provided repository name
get_latest_tag "$1"

