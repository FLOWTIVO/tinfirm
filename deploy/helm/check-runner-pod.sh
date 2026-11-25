#!/usr/bin/env bash
# The chart's part of the pod driver (ADR-0115, decision 2), beyond lint and template:
# - the runs' two network policies and the driver's configuration, rendered with
#   ci/runner-pod-values.yaml, are the files of ci/expected/, which the driver's own tests read
#   (ee/runner/tinfirm-runner-host/src/kubernetes/spec.rs): a change on either side that the
#   other lacks fails one of the two checks;
# - the admission policies render where the cluster serves them, and not with `external`;
# - the runner pod mounts no Secret of the runs' namespace, only the projected token;
# - the chart refuses to render without the operator's statement, and with the runs' namespace
#   equal to the release's.
#
# Run from the repository root: `deploy/helm/check-runner-pod.sh` (the helm job in ci.yml, and
# `make helm-lint`). HELM names the command, `helm` by default, e.g.
# HELM="docker run --rm -v $PWD:/w -w /w alpine/helm:4.3.0".
set -euo pipefail

read -r -a helm <<< "${HELM:-helm}"
chart=deploy/helm/tinfirm
values="$chart/ci/runner-pod-values.yaml"
expected="$chart/ci/expected"
vap=(--api-versions admissionregistration.k8s.io/v1/ValidatingAdmissionPolicy)
render() { "${helm[@]}" template tinfirm "$chart" --namespace tinfirm "$@"; }
fail() { echo "::error::$*"; exit 1; }

# The JSON of an object's `spec` (indented by 2) or of a ConfigMap's key (by 4), as rendered.
spec_of() { # <name>
  awk -v name="$1" '/^---/ { n = "" } /^  name: / { n = $2 } n == name && /^spec:$/ { on = 1; next }
    on && /^[^ ]/ { on = 0 } on && n == name && length($0) { sub(/^  /, ""); print }'
}
key_of() { # <key>
  awk -v key="  $1: |" '$0 == key { on = 1; next } on && !/^    / { on = 0 } on { sub(/^    /, ""); print }'
}

out="$(render -f "$values" --show-only templates/runs-networkpolicy.yaml)"
diff -u "$expected/runs-default-deny.json" <(spec_of tinfirm-runs-default-deny <<< "$out") \
  || fail "The runs' default-deny policy differs from $expected/runs-default-deny.json: change both, and spec.rs's default_deny_spec with them"
diff -u "$expected/runs-allow-runner.json" <(spec_of tinfirm-runs-allow-runner <<< "$out") \
  || fail "The runs' allow rule differs from $expected/runs-allow-runner.json: change both, and spec.rs's allow_runner_spec with them"
out="$(render -f "$values" --show-only templates/runner-configmap.yaml)"
diff -u "$expected/pod-driver.json" <(key_of pod-driver.json <<< "$out") \
  || fail "The pod driver's configuration differs from $expected/pod-driver.json: change both, and config.rs's PodConfig with them"

# Exactly two policies in the runs' namespace: the driver reports any third one.
for f in "$values" "$chart/ci/runner-pod-init-values.yaml"; do
  count="$(render -f "$f" "${vap[@]}" | awk '/^kind: NetworkPolicy$/ { p = 1 } /^---/ { p = 0 }
    p && /^  namespace: / { print $2 }' | grep -c -x -e tinfirm-runs -e sandboxes || true)"
  [ "$count" = 2 ] || fail "$f: $count NetworkPolicies in the runs' namespace, not the chart's two"
done

# The admission policies: two policies and two bindings by capability, none without it or with
# `external`.
count() { grep -c -E '^kind: ValidatingAdmissionPolicy(Binding)?$' || true; }
[ "$(render -f "$values" "${vap[@]}" | count)" = 4 ] || fail "No admission policies with the API served"
[ "$(render -f "$values" | count)" = 0 ] || fail "Admission policies without the API served"
[ "$(render -f "$chart/ci/runner-pod-init-values.yaml" "${vap[@]}" | count)" = 0 ] \
  || fail "Admission policies with runner.vm.cluster.admission: external"

# The pod policy's rules of placement, of the disk's claim, of Multus's annotations and of
# what a container runs, in every configuration of the two ci/ files: the battery's pods for
# them (spec.rs, admission_battery) are refused each by its rule only in a cluster, which the
# kind test shows; here each rule is there, with the configured values in it. The commands and
# the environment's names are spec.rs's, whose own test reads them in the template.
has_rule() { # <what> <fixed string of the rendered policy>
  grep -q -F -e "$2" <<< "$policy" || fail "$name: the pod policy lacks its rule on $1 ($2)"
}
for name in multus ephemeral init init-emptydir; do
  case "$name" in
    multus) args=(-f "$values") ;;
    ephemeral) args=(-f "$values" --set runner.vm.pod.disk.volume=ephemeral) ;;
    init) args=(-f "$chart/ci/runner-pod-init-values.yaml" --set runner.vm.cluster.admission=validatingAdmissionPolicy) ;;
    init-emptydir) args=(-f "$chart/ci/runner-pod-init-values.yaml" --set runner.vm.cluster.admission=validatingAdmissionPolicy
      --set runner.vm.pod.disk.volume=emptyDir --set runner.vm.pod.disk.storageClassName=) ;;
  esac
  policy="$(render "${args[@]}" "${vap[@]}" --show-only templates/run-pod-admission.yaml)"
  has_rule "a node's name" "request.operation != 'CREATE' || !has(object.spec.nodeName)"
  has_rule "the scheduler" "object.spec.schedulerName == 'default-scheduler'"
  has_rule "the nodeSelector" 'object.spec.nodeSelector == {"tinfirm.flowtivo.com/kvm":"true"}'
  has_rule "the default tolerations" "t.key == 'node.kubernetes.io/not-ready' || t.key == 'node.kubernetes.io/unreachable'"
  has_rule "the configured toleration" "|| (has(t.key) && t.key == 'tinfirm.flowtivo.com/kvm'"
  has_rule "the anti-affinity" 't.labelSelector.matchLabels == {"app.kubernetes.io/component":"runner","app.kubernetes.io/instance":"tinfirm","app.kubernetes.io/name":"tinfirm"}'
  has_rule "the anti-affinity's namespace" 't.namespaceSelector.matchLabels == {"kubernetes.io/metadata.name":"tinfirm"}'
  has_rule "the anti-affinity's topology" "t.topologyKey == 'kubernetes.io/hostname'"
  has_rule "a pod affinity" "!has(object.spec.affinity.podAffinity)"
  has_rule "the node affinity" "f.key == 'metadata.name' && f.operator == 'NotIn'"
  has_rule "the container's command" "c.command == ['/tinfirm/tinfirm-runner-host', 'vm-pod']"
  has_rule "the container's arguments" "(!has(c.args) || c.args.size() == 0)"
  has_rule "the container's environment" "c.env.map(e, e.name) == ['TINFIRM_POD_NAME', 'TINFIRM_POD_IP', 'TINFIRM_TAP_MODE', 'TINFIRM_TAP_CIDR', 'TINFIRM_BRIDGE_PORT', 'TINFIRM_VCPUS', 'TINFIRM_MEMORY_MIB', 'TINFIRM_DISK_MIB', 'TINFIRM_DISK_VARIANT']"
  has_rule "another default network" "!('v1.multus-cni.io/default-network' in object.metadata.annotations)"
  case "$name" in
    multus | ephemeral)
      has_rule "the tap's attachment" "object.metadata.annotations['k8s.v1.cni.cncf.io/networks'] == 'kube-system/tinfirm-tap@tap0'"
      ;;
    *)
      has_rule "no network attachment" "!('k8s.v1.cni.cncf.io/networks' in object.metadata.annotations)"
      has_rule "the init container's command" "c.command == ['/tinfirm/tinfirm-runner-host', 'vm-pod', 'tap-setup']"
      has_rule "the init container's environment" "c.env.map(e, e.name) == ['TINFIRM_TAP_CIDR']"
      ;;
  esac
  case "$name" in
    ephemeral | init)
      has_rule "one claim" 'object.spec.volumes.filter(v, has(v.ephemeral)).size() == 1'
      for field in dataSource dataSourceRef volumeName selector; do
        has_rule "the claim's $field" "!has(v.ephemeral.volumeClaimTemplate.spec.$field)"
      done
      has_rule "the claim's access modes" "v.ephemeral.volumeClaimTemplate.spec.accessModes == ['ReadWriteOnce']"
      has_rule "the claim's volume mode" "v.ephemeral.volumeClaimTemplate.spec.volumeMode == 'Filesystem'"
      if [ "$name" = init ]; then sizes='["8192Mi"]'; else sizes='["32768Mi","65536Mi"]'; fi
      has_rule "the claim's size" "&& $sizes.exists(s,"
      ;;
    *)
      grep -q -F 'has(v.ephemeral)' <<< "$policy" && fail "$name: an emptyDir disk, but the pod policy admits a claim"
      ;;
  esac
done

# The driver's Role: network policies listed, never read one by one, created or changed.
out="$(render -f "$values" --show-only templates/runner-driver-rbac.yaml)"
grep -A1 -x '    resources: \[networkpolicies\]' <<< "$out" | grep -q -x '    verbs: \[list\]' \
  || fail "The driver's Role must allow network policies [list] and nothing else"

# The runner pod: its own ServiceAccount, no automatic token, and of the runs' namespace
# nothing but the projected token.
out="$(render -f "$values" --show-only templates/runner-deployment.yaml)"
grep -q -x '      serviceAccountName: tinfirm-runner' <<< "$out" || fail "The runner pod lacks its own ServiceAccount"
grep -q -x '      automountServiceAccountToken: false' <<< "$out" || fail "The runner pod mounts its token automatically"
grep -q -x '              - serviceAccountToken:' <<< "$out" || fail "The runner pod lacks its projected token"
secrets="$(grep -A1 -x '          secret:' <<< "$out" | sed -n 's/^ *secretName: //p')"
[ "$secrets" = tinfirm-runner ] || fail "The runner pod mounts Secrets other than its token's: $secrets"

# The renders that must fail, each with its message.
refused() { # <words of the message> <helm arguments...>
  local words="$1" log
  shift
  if log="$(render -f "$values" "$@" 2>&1)"; then fail "Rendered, but must fail: $*"; fi
  grep -q -F "$words" <<< "$log" || fail "Failed without \"$words\": $log"
}
refused 'runner.vm.cluster.isolationStatement: set it to "the workers for runs hold nothing else"' \
  --set runner.vm.cluster.isolationStatement=
refused 'runner.vm.cluster.isolationStatement' \
  --set 'runner.vm.cluster.isolationStatement=The workers for runs hold nothing else'
refused "runner.vm.cluster.namespace: \"tinfirm\" is the release's namespace" \
  --set runner.vm.cluster.namespace=tinfirm
refused 'networkPolicy.runner.egress.apiServer.to' --set-json 'networkPolicy.runner.egress.apiServer.to=[]'
echo "The chart's pod driver objects are as the driver expects."
