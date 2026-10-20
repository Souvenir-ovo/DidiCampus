#!/usr/bin/env bash
set -euo pipefail

BROKER_CONTAINER="${BROKER_CONTAINER:-didicampus-rmqbroker}"
NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"

admin() {
  docker exec "$BROKER_CONTAINER" sh -c "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin $*"
}

admin "updateTopic -n $NAMESRV -c $CLUSTER -t errand-confirm-timeout -a +message.type=DELAY"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t errand-auto-settle -a +message.type=DELAY"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t errand-cache-evict -a +message.type=DELAY"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t errand-fund-event -a +message.type=TRANSACTION"

admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g didicampus-timeout-consumer"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g didicampus-auto-settle-consumer"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g didicampus-fund-event-consumer"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g didicampus-cache-evict-consumer"

admin "clusterList -n $NAMESRV"
