#!/bin/bash
# Round 10, on the node as root: what a Docker pull and run write to a VM's disk, on a
# snapshot of the VM image with chunks of 4 KiB and of 32 KiB. A stand-in for a guest: the
# node's own dockerd runs with its data on the mounted snapshot, where the guest's has it.
IMG=/var/lib/tinfirm-node-agent/images/tinfirm-vm.img; W=/var/tmp/chunk-exp; PULL=${PULL:-node:22}
mkdir -p $W/mnt; ORIGIN=$(losetup --find --show --read-only $IMG); SECTORS=$(blockdev --getsz $ORIGIN)
slab() { awk '/^SUnreclaim:/{print $2}' /proc/meminfo; }
echo "the image: $PULL; the node: $(nproc) CPUs, $(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo) MiB"
for CHUNK in 8 64 8 64; do
  rm -f $W/cow; truncate -s 8G $W/cow; COW=$(losetup --find --show $W/cow)
  sync; echo 3 > /proc/sys/vm/drop_caches; S0=$(slab)
  dmsetup create --noudevsync chunk-exp --table "0 $SECTORS snapshot $ORIGIN $COW N $CHUNK"
  DEV=/dev/dm-$(dmsetup info -c --noheadings -o minor chunk-exp); mount -o noatime $DEV $W/mnt
  T0=$(date +%s.%N)
  dockerd --data-root $W/mnt/var/lib/docker --exec-root /run/chunk-exp --host unix:///run/chunk-exp.sock --pidfile /run/chunk-exp.pid --iptables=false --ip6tables=false --bridge=none > $W/dockerd.log 2>&1 &
  until docker -H unix:///run/chunk-exp.sock info >/dev/null 2>&1; do sleep 0.2; done
  T1=$(date +%s.%N)
  docker -H unix:///run/chunk-exp.sock pull -q $PULL >/dev/null 2>$W/pull.err || cat $W/pull.err
  T2=$(date +%s.%N)
  docker -H unix:///run/chunk-exp.sock run --rm --network none $PULL sh -c 'i=0; while [ $i -lt 2000 ]; do echo x > /tmp/f$i; i=$((i+1)); done; dd if=/dev/urandom of=/tmp/big bs=1M count=200 2>/dev/null; sync' >/dev/null 2>&1
  T3=$(date +%s.%N)
  kill "$(cat /run/chunk-exp.pid)"; while [ -e /run/chunk-exp.pid ]; do sleep 0.2; done; sync; T4=$(date +%s.%N)
  STATUS=$(dmsetup status chunk-exp); S1=$(slab)
  echo "chunk $((CHUNK/2)) KiB: dockerd up $(echo "$T1 - $T0" | bc | cut -c1-5) s, pull $(echo "$T2 - $T1" | bc | cut -c1-6) s, run (2000 small files and 200 MiB) $(echo "$T3 - $T2" | bc | cut -c1-5) s, stop and sync $(echo "$T4 - $T3" | bc | cut -c1-5) s, all $(echo "$T4 - $T0" | bc | cut -c1-6) s; the snapshot: $(echo $STATUS | awk '{split($4,a,"/"); printf "%d MiB allocated (%d of them its own tables)", a[1]/2048, $5/2048}'), the file $(( $(stat -c %b $W/cow) / 2048 )) MiB on disk; the kernel's unreclaimable slab grew by $(( (S1 - S0) / 1024 )) MiB"
  umount $W/mnt; sync; echo 3 > /proc/sys/vm/drop_caches; echo "   after the unmount, with the snapshot still there: slab above the start by $(( ($(slab) - S0) / 1024 )) MiB"
  dmsetup remove --noudevsync chunk-exp; losetup -d $COW; rm -f $W/cow
done
losetup -d $ORIGIN; rm -rf $W
for t in $(nft list tables | grep -v -E "security|tinfirm" | sed "s/^table //" | tr " " ":"); do nft delete table ${t%%:*} ${t##*:}; done
echo "docker's tables removed; left: $(nft list tables | tr '\n' ';')"
