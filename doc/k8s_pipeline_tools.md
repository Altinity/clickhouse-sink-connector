### minikube
```bash
wget https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
install minikube-linux-amd64 ~/bin/minikube
rm minikube-linux-amd64 
minikube version
```

```bash
minikube start
minikube status
```

### k9s
```bash
VERSION="v0.25.18"
wget https://github.com/derailed/k9s/releases/download/$VERSION/k9s_Linux_x86_64.tar.gz
install k9s ~/bin/k9s
```

### kubectl

### jq
Ubuntu
```bash
sudo apt-get update && sudo apt-get install jq
```

### helm
```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | HELM_INSTALL_DIR=~/bin USE_SUDO=false bash
helm version
```

### mysql

### clickhouse-client