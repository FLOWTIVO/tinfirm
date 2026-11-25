# The image of a run's pod (ADR-0115, decision 2), for the runner host's `pod` driver. Part of
# elastic runners, with the driver, under the Tinfirm Enterprise licence (ee/LICENSE, ADR-0114);
# the VM image's recipe it holds, runner/images/vm, is in the core.
#
# Do not build it by hand: pod-image.sh, beside this file, checks every part and stages the
# build context, then builds it. The context holds, each already checked:
#
#   tinfirm-runner-host   the release's static binary (its `vm-pod` runs the VM)    0555
#   firecracker           Firecracker's release binary, without its jailer          0555
#   vmlinux               the guest kernel, the one the sandbox nodes run           0444
#   rootfs.ext4           runner/images/vm/local-disk.sh's image, shrunk            0444
#   licenses/             the licences of the binary and of Firecracker
#
# The paths are the run pod's (spec::vm_pod in ee/runner/tinfirm-runner-host/src/kubernetes/
# spec.rs). The image has no shell, no package and no other tool: the pod's program makes the
# tap itself where it must, and runs on a read-only root file system as an unprivileged user.
# The pod's specification sets the command; the entry point here is only its default.
#
# No base image: FROM scratch. The parts that change least come first, so that a new release
# changes the last two layers alone.
FROM scratch
ARG VERSION=unknown
ARG FIRECRACKER_VERSION=unknown
ARG KERNEL=unknown
ARG ROOTFS_SHA256=unknown
LABEL org.opencontainers.image.title="tinfirm-vm-pod" \
      org.opencontainers.image.description="Tinfirm run pod: the runner host's vm-pod, Firecracker, the guest kernel and the sandbox VM image (ADR-0115)" \
      org.opencontainers.image.vendor="FLOWTIVO" \
      org.opencontainers.image.licenses="MIT AND LicenseRef-Tinfirm-Enterprise AND Apache-2.0 AND GPL-2.0-only" \
      org.opencontainers.image.version="${VERSION}" \
      com.flowtivo.tinfirm.firecracker="${FIRECRACKER_VERSION}" \
      com.flowtivo.tinfirm.kernel="${KERNEL}" \
      com.flowtivo.tinfirm.rootfs.sha256="${ROOTFS_SHA256}"
COPY licenses/ /licenses/
COPY vmlinux /tinfirm/vm/vmlinux
COPY firecracker /tinfirm/vm/firecracker
COPY rootfs.ext4 /tinfirm/vm/rootfs.ext4
COPY tinfirm-runner-host /tinfirm/tinfirm-runner-host
USER 10001:10001
ENTRYPOINT ["/tinfirm/tinfirm-runner-host", "vm-pod"]
