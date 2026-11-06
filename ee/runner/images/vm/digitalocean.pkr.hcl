# The snapshot of DigitalOcean's sandbox Droplets (ADR-0096): a private snapshot of your
# DigitalOcean team, never a public image. Build it for each release of the runner host,
# then name its id as `image` in the driver's template (runner/README.md, "A VM per run"):
#
#   export DIGITALOCEAN_TOKEN=...   # a token that may create Droplets and snapshots
#   packer init ee/runner/images/vm/digitalocean.pkr.hcl
#   packer build -var binary=./tinfirm-runner-host-x86_64-linux-musl -var version=0.3.0 \
#       -var region=fra1 ee/runner/images/vm/digitalocean.pkr.hcl
#
# A snapshot is only in the regions it was built in or copied to, and the driver refuses a
# template whose image is not in its region. Build in the region of the sandboxes, and copy the
# snapshot to every other region a template uses: -var 'snapshot_regions=["nyc3","sgp1"]'.
#
# The build Droplet is an ordinary one with network access, and carries no `tinfirm-sandbox`
# tag: the sandboxes' firewall would cut Packer off.

packer {
  required_plugins {
    digitalocean = {
      version = ">= 1.4.0"
      source  = "github.com/digitalocean/digitalocean"
    }
  }
}

variable "binary" {
  type        = string
  description = "A static Linux build of tinfirm-runner-host (a release binary) for the Droplets' architecture."
}

variable "version" {
  type        = string
  description = "The runner host's version, for the snapshot's name."
}

variable "region" {
  type        = string
  description = "The region to build in: one where the sandboxes run, a template's `region`."
}

variable "snapshot_regions" {
  type        = list(string)
  default     = []
  description = "The other regions the templates use, which the snapshot is copied to."
}

variable "size" {
  type    = string
  default = "s-4vcpu-8gb"
}

variable "image_build_args" {
  type        = string
  default     = ""
  description = "`--build-arg KEY=VALUE` options for `images build` (mirrors; never a credential)."
}

source "digitalocean" "sandbox" {
  image            = "debian-12-x64"
  region           = var.region
  snapshot_regions = var.snapshot_regions
  size             = var.size
  ssh_username     = "root"
  droplet_name     = "tinfirm-sandbox-build"
  snapshot_name    = "tinfirm-sandbox-${var.version}-{{timestamp}}"
  tags             = ["tinfirm-sandbox-build"]
}

build {
  sources = ["source.digitalocean.sandbox"]

  provisioner "file" {
    source      = var.binary
    destination = "/tmp/tinfirm-runner-host"
  }

  provisioner "file" {
    source      = "${path.root}/tinfirm-vm-boot.service"
    destination = "/tmp/tinfirm-vm-boot.service"
  }

  provisioner "shell" {
    script           = "${path.root}/setup.sh"
    environment_vars = ["IMAGE_BUILD_ARGS=${var.image_build_args}"]
  }
}
