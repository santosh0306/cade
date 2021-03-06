resource "docker_container" "{SERVICE_NAME}-{NODE_ID}" {
  provider = "docker.node-{NODE_ID}"

  image = "{SERVICE_IMAGE}"
  name  = "{SERVICE_NAME}"
  {COMMAND_CUSTOM}
  env = [
    "WEAVE_CIDR={CONTAINER_IP}/12",
    "CONTAINER_IP={CONTAINER_IP}",
    "CONTAINER_NAME={SERVICE_NAME}",
    "CONTAINER_ID={CONTAINER_ID}",
    "NODE_ID={NODE_ID}",
    "SERVICE_NAME={SERVICE_NAME}.cade.local"{ENV_PUBLIC_HOST_IP}{ENV_SERVICE_SEEDS}{ENV_DEPENDENCIES}{ENV_CUSTOM}
  ]
  restart = "always"
  hostname = "{SERVICE_NAME}.cade.local"
  ports = [{PORTS_CUSTOM}]
  destroy_grace_seconds = 30
  volumes = [
    { host_path = "{VOLUME}/docker-init", container_path = "/init", read_only = true}{VOLUME_CUSTOM}{VOLUME_FILES}
  ]

  {LOGGING}

  {CAPABILITIES}

  # TODO breaks containers without command line specified in YAML file
  #entrypoint = [ "/init", "-s", "--" ]
}
