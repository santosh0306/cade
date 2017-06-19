#!/bin/sh

#
# License: https://github.com/webintrinsics/clusterlite/blob/master/LICENSE
#

# set -e :: can not set to an error, because it is expected some call are failed

if [ -z "$7" ];
then
    echo "[clusterlite proxy-allocate] internal error detected, proxy-allocate should be invoked with 7 arguments, exiting..."
    exit 1
fi

curl --fail -s http://clusterlite-etcd:2379/v2/keys
while [ $? -ne 0 ]; do
    echo "[clusterlite proxy-allocate] waiting for clusterlite-etcd service"
    sleep 1
    curl --fail http://clusterlite-etcd:2379/v2/keys
done

# bootstrap storage
curl --fail -s http://clusterlite-etcd:2379/v2/keys/nodes
if [ $? -ne 0 ]; then
    echo "[clusterlite proxy-allocate] bootstraping clusterlite-etcd storage"
    curl --fail -XPUT http://clusterlite-etcd:2379/v2/keys/nodes -d dir=true || echo ""
fi

# weaveid, token, volume, placement, public_ip, seeds, seed_id
data="$1,$2,$3,$4,$5,${6//,/:},$7"

current_id=1
echo "[clusterlite proxy-allocate] scanning for the next available node id: ${current_id}"
curl --fail -s -X PUT http://clusterlite-etcd:2379/v2/keys/nodes/${current_id}?prevExist=false -d value="${data}"
while [ $? -ne 0 ]; do
    current_id=$((current_id+1))
    echo "[clusterlite proxy-allocate] scanning for the next available node id: ${current_id}"
    curl --fail -s -X PUT http://clusterlite-etcd:2379/v2/keys/nodes/${current_id}?prevExist=false -d value="${data}"
done
echo "[clusterlite proxy-allocate] allocated node id: ${current_id}"
echo ${current_id} > /data/nodeid.txt