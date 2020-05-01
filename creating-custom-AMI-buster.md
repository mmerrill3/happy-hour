Creating Custom AMI image for debian Buster
===

## Installing

I forked the upstream project https://github.com/justinsb/bootstrap-vz.  Within this fork, in the image18 branch, we need to apply a couple of patches to enable buster.

Important:  There are fixes in the image18 branch you need that are there already for device drivers.  See [Device Driver Issue](https://github.com/andsens/bootstrap-vz/issues/402)

In the file bootstrapvz/plugins/cloud_init/manifest-schema.yml, we need to add buster as an acceptable release:

```
properties:
  system:
    type: object
    properties:
      release:
        type: string
        enum:
        - wheezy
        - oldstable
        - jessie
        - stable
        - stretch
        - buster
        - testing
        - sid
        - unstable
```

In the file bootstrapvz/providers/ec2/tasks/packages-kernels.yml, we add the kernel information for buster:

```
# This is a mapping of Debian release codenames to processor architectures to kernel packages
squeeze: # In squeeze, we need a special kernel flavor for xen
  amd64: linux-image-xen-amd64
  i386: linux-image-xen-686
wheezy:
  amd64: linux-image-amd64
  i386: linux-image-686
jessie:
  amd64: linux-image-amd64
  i386: linux-image-686-pae
stretch:
  amd64: linux-image-amd64
  i386: linux-image-686-pae
buster:
  amd64: linux-image-amd64
  i386: linux-image-686-pae
sid:
  amd64: linux-image-amd64
  i386: linux-image-686-pae
```


Great, publish that to your fork, and you're almost all set.  Now, checkout the image builder:

```
go get sigs.k8s.io/image-builder/images/kube-deploy/imagebuilder
```

Let's create two files for buster.  Go into the project:

```
cd ${GOPATH}/src/sigs.k8s.io/image-builder/images/kube-deploy/imagebuilder`
cat <<EOF > aws-1.17-buster.yaml
Cloud: aws
TemplatePath: templates/1.17-buster.yml
Tags:
  k8s.io/kernel: "4.19"
  k8s.io/version: "1.17"
  k8s.io/family: "default"
  k8s.io/distro: "debian"
  k8s.io/ssh-user: "admin"
# Ensure the image is repeatable - really we should be locking to a tag
BootstrapVZRepo: https://github.com/mmerrill3/bootstrap-vz.git
BootstrapVZBranch: image18
EOF
cat <<EOF > templates/1.17-buster.yml
---
{{ if eq .Cloud "aws" }}
name: k8s-1.17-debian-{system.release}-{system.architecture}-{provider.virtualization}-ebs-{%Y}-{%m}-{%d}
{{ else }}
name: k8s-1.17-debian-{system.release}-{system.architecture}-{%Y}-{%m}-{%d}
{{ end }}
provider:
{{ if eq .Cloud "aws" }}
  name: ec2
  virtualization: hvm
  enhanced_networking: simple
{{ else if eq .Cloud "gce" }}
  name: gce
  gcs_destination: {{ .GCSDestination }}
  gce_project: {{ .Project }}
{{ else }}
  name: {{ .Cloud }}
{{ end }}
  description: Kubernetes 1.17 Base Image - Debian {system.release} {system.architecture}
bootstrapper:
  workspace: /target
  # tarball speeds up development, but for prod builds we want to be 100% sure...
  # tarball: true
  # todo: switch to variant: minbase
system:
  release: buster
  architecture: amd64
  bootloader: grub
  charmap: UTF-8
  locale: en_US
  timezone: UTC
volume:
{{ if eq .Cloud "aws" }}
  backing: ebs
{{ else if eq .Cloud "gce" }}
  backing: raw
{{ end }}
  partitions:
    type: gpt
    root:
      filesystem: ext4
      # We create the FS with more inodes... docker is pretty inode hungry
      format_command: [ 'mkfs.{fs}', '-i', '4096', '{device_path}' ]
      size: 8GiB
packages:
{{ if eq .Cloud "aws" }}
  mirror: http://cloudfront.debian.net/debian
{{ end }}
  install:
    # Important utils for administration
    # if minbase - openssh-server

    # Ensure systemd scripts run on shutdown
    - acpi-support

    # these packages are generally useful
    # (and are the ones from the GCE image)
    - rsync
    - screen
    - vim

    # needed for docker
    - arptables
    - ebtables
    - iptables
    - libapparmor1
    - libltdl7

    # Handy utilities
    - htop
    - tcpdump
    - iotop
    - ethtool
    - sysstat

    # needed for setfacl below
    - acl

{{ if eq .Cloud "aws" }}
    # these packages are included in the official AWS image
    - python-boto
    - python3-boto
    - apt-transport-https
    - lvm2
    - ncurses-term
    - parted
    - cloud-init
    - cloud-utils
    - gdisk
    - systemd
    - systemd-sysv

    # these packages are included in the official image, but we remove them
    # awscli : we install from pip instead
{{ end }}

    # These packages would otherwise be installed during first boot
    - aufs-tools
    - curl
    - python-yaml
    - git
    - nfs-common
    - bridge-utils
    - logrotate
    - socat
    - python-apt
    - apt-transport-https
    - unattended-upgrades
    - lvm2
    - btrfs-tools

{{ if eq .Cloud "aws" }}
    # So we can install the latest awscli
    - python-pip
{{ end }}

plugins:
{{ if eq .Cloud "gce" }}
  ntp:
    servers:
    - metadata.google.internal
{{ else }}
  ntp: {}
{{ end }}

{{ if eq .Cloud "aws" }}
  cloud_init:
    metadata_sources: Ec2
    username: admin
    enable_modules:
      cloud_init_modules:
        - {module: growpart, position: 4}
{{ end }}

  commands:
    commands:
{{ if eq .Cloud "aws" }}
       # Install awscli through python-pip
       - [ 'chroot', '{root}', 'pip', 'install', 'awscli' ]
{{ end }}

       # We don't enable unattended upgrades - nodeup can always add it
       # but if we add it now, there's a race to turn it off
       # cloud-init depends on unattended-upgrades, so we can't just remove it
       # Instead we turn them off; we turn them on later
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "APT::Periodic::Update-Package-Lists \"0\";" > /etc/apt/apt.conf.d/20auto-upgrades' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "APT::Periodic::Unattended-Upgrade \"0\"; " >> /etc/apt/apt.conf.d/20auto-upgrades' ]
       # - [ 'chroot', '{root}', 'apt-get', 'remove', '--yes', 'unattended-upgrades' ]

       # Install Docker
       - [ 'mkdir', '-p', '{root}/var/cache/nodeup/packages' ]
       - [ 'wget', 'https://download.docker.com/linux/debian/dists/buster/pool/stable/amd64/containerd.io_1.2.10-3_amd64.deb', '-O', '{root}/var/cache/nodeup/packages/containerd.io.deb' ]
       - [ '/bin/sh', '-c', 'cd {root}/var/cache/nodeup/packages; echo "365e4a7541ce2cf3c3036ea2a9bf6b40a50893a8  containerd.io.deb" | shasum -c -' ]
       - [ 'wget', 'https://download.docker.com/linux/debian/dists/buster/pool/stable/amd64/docker-ce-cli_19.03.4~3-0~debian-buster_amd64.deb', '-O', '{root}/var/cache/nodeup/packages/docker-ce-cli.deb' ]
       - [ '/bin/sh', '-c', 'cd {root}/var/cache/nodeup/packages; echo "2549a364f0e5ce489c79b292b78e349751385dd5  docker-ce-cli.deb" | shasum -c -' ]
       - [ 'wget', 'https://download.docker.com/linux/debian/dists/buster/pool/stable/amd64/docker-ce_19.03.4~3-0~debian-buster_amd64.deb', '-O', '{root}/var/cache/nodeup/packages/docker-ce.deb' ]
       - [ '/bin/sh', '-c', 'cd {root}/var/cache/nodeup/packages; echo "492a70f29ceffd315ee9712b33004491c6f59e49  docker-ce.deb" | shasum -c -' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'DEBIAN_FRONTEND=noninteractive dpkg --install /var/cache/nodeup/packages/containerd.io.deb' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'DEBIAN_FRONTEND=noninteractive dpkg --install /var/cache/nodeup/packages/docker-ce-cli.deb' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'DEBIAN_FRONTEND=noninteractive dpkg --install /var/cache/nodeup/packages/docker-ce.deb' ]
       - [ 'chroot', '{root}', 'systemctl', 'disable', 'containerd.service', 'docker.service', 'docker.socket' ]

       # We perform a full replacement of some grub conf variables:
       #   GRUB_CMDLINE_LINUX_DEFAULT (add memory cgroup)
       #   GRUB_TIMEOUT (remove boot delay)
       # (but leave the old versions commented out for people to see)
       - [ 'chroot', '{root}', 'touch', '/etc/default/grub' ]
       - [ 'chroot', '{root}', 'sed', '-i', 's/^GRUB_CMDLINE_LINUX_DEFAULT=/#GRUB_CMDLINE_LINUX_DEFAULT=/g', '/etc/default/grub' ]
       - [ 'chroot', '{root}', 'sed', '-i', 's/^GRUB_TIMEOUT=/#GRUB_TIMEOUT=/g', '/etc/default/grub' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "# kubernetes image changes" >> /etc/default/grub' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "GRUB_CMDLINE_LINUX_DEFAULT=\"cgroup_enable=memory oops=panic panic=10 console=ttyS0 nvme_core.io_timeout=255\"" >> /etc/default/grub' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "GRUB_TIMEOUT=0" >> /etc/default/grub' ]
       - [ 'chroot', '{root}', 'update-grub2' ]

       # Update everything to latest versions
       - [ 'chroot', '{root}', 'apt-get', 'update' ]
       - [ 'chroot', '{root}', 'apt-get', 'dist-upgrade', '--yes' ]

       # Cleanup packages
       - [ 'chroot', '{root}', 'apt-get', 'autoremove', '--yes' ]

       # Remove machine-id, so that we regenerate next boot
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "" > /etc/machine-id' ]

       # Ensure we have cleaned up all our SSH keys
       - [ 'chroot', '{root}', 'bin/sh', '-c', 'shred --remove /etc/ssh/ssh_host_*_key' ]
       - [ 'chroot', '{root}', 'bin/sh', '-c', 'shred --remove /etc/ssh/ssh_host_*_key.pub' ]
       # Workaround bootstrap-vz bug where it errors if all keys are removed
       - [ 'chroot', '{root}', 'bin/sh', '-c', 'touch /etc/ssh/ssh_host_rsa_key.pub' ]

       # journald requires machine-id, so add a PreStart
       - [ 'chroot', '{root}', 'mkdir', '-p', '/etc/systemd/system/debian-fixup.service.d/' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "[Service]" > /etc/systemd/system/debian-fixup.service.d/10-machineid.conf' ]
       - [ 'chroot', '{root}', '/bin/sh', '-c', 'echo "ExecStartPre=/bin/systemd-machine-id-setup" >> /etc/systemd/system/debian-fixup.service.d/10-machineid.conf' ]

       # Make sure journald is persistent
       # From /usr/share/doc/systemd/README.Debian
       - [ 'chroot', '{root}', 'install', '-d', '-g', 'systemd-journal', '/var/log/journal' ]
       - [ 'chroot', '{root}', 'setfacl', '-R', '-nm', 'g:adm:rx,d:g:adm:rx', '/var/log/journal' ]

       # Use iptables-legacy by default
       - [ 'chroot', '{root}', 'update-alternatives', '--set', 'iptables', '/usr/sbin/iptables-legacy' ]
       - [ 'chroot', '{root}', 'update-alternatives', '--set', 'ip6tables', '/usr/sbin/ip6tables-legacy' ]
       - [ 'chroot', '{root}', 'update-alternatives', '--set', 'arptables', '/usr/sbin/arptables-legacy' ]
       - [ 'chroot', '{root}', 'update-alternatives', '--set', 'ebtables', '/usr/sbin/ebtables-legacy' ]
EOF
```


Awesome, just one more thing, we need to patch one of the go files so buster can be create on an EC2 instance type this is supported in your region in AWS.



```
cat <<EOF > pkg/imagebuilder/config.go
package imagebuilder

import (
	"strings"

	"k8s.io/klog"
)

type Config struct {
	Cloud         string
	TemplatePath  string
	SetupCommands [][]string

	BootstrapVZRepo   string
	BootstrapVZBranch string

	SSHUsername   string
	SSHPublicKey  string
	SSHPrivateKey string

	InstanceProfile string

	// Tags to add to the image
	Tags map[string]string
}

func (c *Config) InitDefaults() {
	c.BootstrapVZRepo = "https://github.com/andsens/bootstrap-vz.git"
	c.BootstrapVZBranch = "master"

	c.SSHUsername = "admin"
	c.SSHPublicKey = "~/.ssh/id_rsa.pub"
	c.SSHPrivateKey = "~/.ssh/id_rsa"

	c.InstanceProfile = ""

	setupCommands := []string{
		"sudo apt-get update",
		"sudo apt-get install --yes git python debootstrap python-pip kpartx parted",
		"sudo pip install --upgrade requests termcolor jsonschema fysom docopt pyyaml boto boto3",
	}
	for _, cmd := range setupCommands {
		c.SetupCommands = append(c.SetupCommands, strings.Split(cmd, " "))
	}
}

type AWSConfig struct {
	Config

	Region          string
	ImageID         string
	InstanceType    string
	SSHKeyName      string
	SubnetID        string
	SecurityGroupID string
	Tags            map[string]string
}

func (c *AWSConfig) InitDefaults(region string) {
	c.Config.InitDefaults()
	c.InstanceType = "c4.large"

	if region == "" {
		region = "us-east-1"
	}

	c.Region = region
	switch c.Region {
	case "cn-north-1":
		klog.Infof("Detected cn-north-1 region")
		// A slightly older image, but the newest one we have
		c.ImageID = "ami-da69a1b7"

	// Debian 10.3 images from https://wiki.debian.org/Cloud/AmazonEC2Image/Buster
	case "ap-east-1":
		c.ImageID = "ami-f9c58188"
	case "ap-northeast-1":
		c.ImageID = "ami-0fae5501ae428f9d7"
	case "ap-northeast-2":
		c.ImageID = "ami-0522874b039290246"
	case "ap-south-1":
		c.ImageID = "ami-03b4e18f70aca8973"
	case "ap-southeast-1":
		c.ImageID = "ami-0852293c17f5240b3"
	case "ap-southeast-2":
		c.ImageID = "ami-03ea2db714f1f6acf"
	case "ca-central-1":
		c.ImageID = "ami-094511e5020cdea18"
	case "eu-central-1":
		c.ImageID = "ami-0394acab8c5063f6f"
	case "eu-north-1":
		c.ImageID = "ami-0c82d9a7f5674320a"
	case "eu-west-1":
		c.ImageID = "ami-006d280940ad4a96c"
	case "eu-west-2":
		c.ImageID = "ami-08fe9ea08db6f1258"
	case "eu-west-3":
		c.ImageID = "ami-04563f5eab11f2b87"
	case "me-south-1":
		c.ImageID = "ami-0492a01b319d1f052"
	case "sa-east-1":
		c.ImageID = "ami-05e16feea94258a69"
	case "us-east-1":
		c.ImageID = "ami-04d70e069399af2e9"
	case "us-east-2":
		c.ImageID = "ami-04100f1cdba76b497"
	case "us-west-1":
		c.ImageID = "ami-014c78f266c5b7163"
	case "us-west-2":
		c.ImageID = "ami-023b7a69b9328e1f9"

	default:
		klog.Warningf("Building in unknown region %q - will require specifying an image, may not work correctly")
	}
}

type GCEConfig struct {
	Config

	// To create an image on GCE, we have to upload it to a bucket first
	GCSDestination string

	Project     string
	Zone        string
	MachineName string

	MachineType string
	Image       string
	Tags        map[string]string
}

func (c *GCEConfig) InitDefaults() {
	c.Config.InitDefaults()
	c.MachineName = "k8s-imagebuilder"
	c.Zone = "us-central1-f"
	c.MachineType = "n1-standard-2"
	c.Image = "https://www.googleapis.com/compute/v1/projects/debian-cloud/global/images/debian-8-jessie-v20160329"
}
EOF
```

Nice!  Now we need to rebuild imagebuilder.  Don't use the default make command, since it will overwrite your changes since go will go to the remote repo and compile what is there, not what is local.  To compile and install what is local, run:

```
GO111MODULE=on go install .
```

Now, we have our own imagebuilder in the ${GOPATH}/bin directory.


We can't run it quite yet...  We need to configure our VPC in AWS with the right tags so imagebuild knows which internet gateway to use, and which security groups.  Also, make sure you have your ssh keys setup in your ${HOME}/.ssh folder, namely id_rsa.  That's the ssh key pair that will be used during the image building process.

Thankfully, there's a shell script in the hack directory to do all of this for us.  Just run it, but here is the script:

```
mmerrillmbp:imagebuilder mmerrill$ cat hack/setup-aws.sh 
#!/bin/bash

echo Starting aws setup

# this does not working us-east-2 yet
# export AWS_REGION=us-west-1

export VPC_ID=`aws ec2 create-vpc --cidr-block 172.20.0.0/16 --query Vpc.VpcId --output text`
aws ec2 create-tags --resources ${VPC_ID} --tags Key=k8s.io/role/imagebuilder,Value=1

export SUBNET_ID=`aws ec2 create-subnet --cidr-block 172.20.1.0/24 --vpc-id ${VPC_ID} --query Subnet.SubnetId --output text`
aws ec2 create-tags --resources ${SUBNET_ID} --tags Key=k8s.io/role/imagebuilder,Value=1


export IGW_ID=`aws ec2 create-internet-gateway --query InternetGateway.InternetGatewayId --output text`
aws ec2 create-tags --resources ${IGW_ID} --tags Key=k8s.io/role/imagebuilder,Value=1

aws ec2 attach-internet-gateway --internet-gateway-id ${IGW_ID} --vpc-id ${VPC_ID}

export RT_ID=`aws ec2 describe-route-tables --filters Name=vpc-id,Values=${VPC_ID} --query RouteTables[].RouteTableId --output text`

export SG_ID=`aws ec2 create-security-group --vpc-id ${VPC_ID} --group-name imagebuilder --description "imagebuilder security group" --query GroupId --output text`
aws ec2 create-tags --resources ${SG_ID} --tags Key=k8s.io/role/imagebuilder,Value=1

aws ec2 associate-route-table --route-table-id ${RT_ID} --subnet-id ${SUBNET_ID}

aws ec2 create-route --route-table-id ${RT_ID} --destination-cidr-block 0.0.0.0/0 --gateway-id ${IGW_ID}

aws ec2 authorize-security-group-ingress  --group-id ${SG_ID} --protocol tcp --port 22 --cidr 0.0.0.0/0

IMGBUILDER_DIRECTORY="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd $IMGBUILDER_DIRECTORY/..

export AWS_REGION=$(aws configure get region)
imagebuilder --config aws.yaml --v=8

echo We are done, and there be dragons!
```

Ok, run imagebuilder after your AWS components are all set.  This will make public AMI images in all of the regions that were in the patched go file above, and their corresponding snapshots.  AMI and snapshots are local to the regions.  You might want to clean them up when done since you will pay for the storage of these snapshots.

```
imagebuilder --config aws-1.17-buster.yaml --v=8
```

Voila!  We have our published AMI's for buster.



To remove the work, you can undo the AWS resources made above.  But, here is how to remove the snapshots and public images via a script.  You need to put in your AWS account id.

```
#!/bin/bash
if [ -z "$1" ] ; then
    echo "Please pass the name of the AMI"
    exit 1
fi

IMAGE_FILTER="${1}"

declare -a REGIONS=($(aws ec2 describe-regions --output json | jq '.Regions[].RegionName' | tr '\n' ' ' | tr -d '"'))
for r in "${REGIONS[@]}" ; do
    ami=$(aws ec2 describe-images --owners <MY AWS ACCOUNT ID> --query 'Images[*].[ImageId]' --filters "Name=name,Values=${IMAGE_FILTER}" --region ${r} --output json | jq '.[0][0]' | tr -d '"')
    aws ec2 deregister-image --region ${r} --image-id ${ami}
    snapshot=$(aws ec2 describe-snapshots --owner-ids <MY AWS ACCOUNT ID> --query 'Snapshots[*].[SnapshotId]' --region ${r} --output json | jq '.[0][0]' | tr -d '"')
    if [ "$snapshot" != "null" ]; then
      echo "aws ec2 delete-snapshot --region ${r} --snapshot-id ${snapshot}"
      aws ec2 delete-snapshot --region ${r} --snapshot-id ${snapshot}
    fi
done
```