"use strict";(self.webpackChunkclickhouse_sink_connector=self.webpackChunkclickhouse_sink_connector||[]).push([[706],{3905:(e,n,t)=>{t.d(n,{Zo:()=>d,kt:()=>k});var r=t(7294);function c(e,n,t){return n in e?Object.defineProperty(e,n,{value:t,enumerable:!0,configurable:!0,writable:!0}):e[n]=t,e}function a(e,n){var t=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);n&&(r=r.filter((function(n){return Object.getOwnPropertyDescriptor(e,n).enumerable}))),t.push.apply(t,r)}return t}function o(e){for(var n=1;n<arguments.length;n++){var t=null!=arguments[n]?arguments[n]:{};n%2?a(Object(t),!0).forEach((function(n){c(e,n,t[n])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(t)):a(Object(t)).forEach((function(n){Object.defineProperty(e,n,Object.getOwnPropertyDescriptor(t,n))}))}return e}function i(e,n){if(null==e)return{};var t,r,c=function(e,n){if(null==e)return{};var t,r,c={},a=Object.keys(e);for(r=0;r<a.length;r++)t=a[r],n.indexOf(t)>=0||(c[t]=e[t]);return c}(e,n);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);for(r=0;r<a.length;r++)t=a[r],n.indexOf(t)>=0||Object.prototype.propertyIsEnumerable.call(e,t)&&(c[t]=e[t])}return c}var s=r.createContext({}),l=function(e){var n=r.useContext(s),t=n;return e&&(t="function"==typeof e?e(n):o(o({},n),e)),t},d=function(e){var n=l(e.components);return r.createElement(s.Provider,{value:n},e.children)},p="mdxType",u={inlineCode:"code",wrapper:function(e){var n=e.children;return r.createElement(r.Fragment,{},n)}},m=r.forwardRef((function(e,n){var t=e.components,c=e.mdxType,a=e.originalType,s=e.parentName,d=i(e,["components","mdxType","originalType","parentName"]),p=l(t),m=c,k=p["".concat(s,".").concat(m)]||p[m]||u[m]||a;return t?r.createElement(k,o(o({ref:n},d),{},{components:t})):r.createElement(k,o({ref:n},d))}));function k(e,n){var t=arguments,c=n&&n.mdxType;if("string"==typeof e||c){var a=t.length,o=new Array(a);o[0]=m;var i={};for(var s in n)hasOwnProperty.call(n,s)&&(i[s]=n[s]);i.originalType=e,i[p]="string"==typeof e?e:c,o[1]=i;for(var l=2;l<a;l++)o[l]=t[l];return r.createElement.apply(null,o)}return r.createElement.apply(null,t)}m.displayName="MDXCreateElement"},4387:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>s,contentTitle:()=>o,default:()=>u,frontMatter:()=>a,metadata:()=>i,toc:()=>l});var r=t(7462),c=(t(7294),t(3905));const a={},o="debezium",i={unversionedId:"doc/k8s_build_connect_images",id:"doc/k8s_build_connect_images",title:"debezium",description:"create secret",source:"@site/docs/doc/k8s_build_connect_images.md",sourceDirName:"doc",slug:"/doc/k8s_build_connect_images",permalink:"/doc/k8s_build_connect_images",draft:!1,tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"mysql_replication",permalink:"/doc/img/mysql_replication"},next:{title:"k8s_minikube_load",permalink:"/doc/k8s_minikube_load"}},s={},l=[{value:"create secret",id:"create-secret",level:3},{value:"create secret",id:"create-secret-1",level:3}],d={toc:l},p="wrapper";function u(e){let{components:n,...t}=e;return(0,c.kt)(p,(0,r.Z)({},d,t,{components:n,mdxType:"MDXLayout"}),(0,c.kt)("h1",{id:"debezium"},"debezium"),(0,c.kt)("h3",{id:"create-secret"},"create secret"),(0,c.kt)("pre",null,(0,c.kt)("code",{parentName:"pre",className:"language-bash"},"NAMESPACE=debezium\nkubectl create ns ${NAMESPACE}\nkubectl -n ${NAMESPACE} create secret generic docker-access-secret \\\n  --from-file=.dockerconfigjson=${HOME}/.docker/config.json \\\n  --type=kubernetes.io/dockerconfigjson\n")),(0,c.kt)("pre",null,(0,c.kt)("code",{parentName:"pre",className:"language-yaml"},"apiVersion: v1\nkind: Secret\nmetadata:\n  namespace: debezium\n  name: docker-access-secret\ntype: kubernetes.io/dockerconfigjson\ndata:\n  .dockerconfigjson: cat ~/.docker/config.json | base64\n")),(0,c.kt)("h1",{id:"sink"},"sink"),(0,c.kt)("h3",{id:"create-secret-1"},"create secret"),(0,c.kt)("pre",null,(0,c.kt)("code",{parentName:"pre",className:"language-bash"},'NAMESPACE="sink"\nkubectl create namespace "${NAMESPACE}"\nkubectl -n ${NAMESPACE} create secret generic docker-access-secret \\\n  --from-file=.dockerconfigjson=${HOME}/.docker/config.json \\\n  --type=kubernetes.io/dockerconfigjson\n')),(0,c.kt)("pre",null,(0,c.kt)("code",{parentName:"pre",className:"language-yaml"},"apiVersion: v1\nkind: Secret\nmetadata:\n  namespace: debezium\n  name: docker-access-secret\ntype: kubernetes.io/dockerconfigjson\ndata:\n  .dockerconfigjson: cat ~/.docker/config.json | base64\n")),(0,c.kt)("pre",null,(0,c.kt)("code",{parentName:"pre",className:"language-bash"},"BASE=$(pwd)\nmvn clean compile package\nrm -f ${BASE}/deploy/k8s/artefacts/*.tgz\n(cd ${BASE}/deploy/libs; find . -name '*.jar' | xargs tar czvf ${BASE}/deploy/k8s/artefacts/libs.tgz)\n(cd ${BASE}/target;      find . -name '*.jar' | xargs tar czvf ${BASE}/deploy/k8s/artefacts/sink.tgz)\n")))}u.isMDXComponent=!0}}]);