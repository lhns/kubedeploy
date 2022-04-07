# kubedeploy

[![Docker Workflow](https://github.com/lhns/kubedeploy/workflows/build/badge.svg)](https://github.com/lhns/kubedeploy/actions?query=workflow%3Abuild)
[![Release Notes](https://img.shields.io/github/release/lhns/kubedeploy.svg?maxAge=3600)](https://github.com/lhns/kubedeploy/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/kubedeploy.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

A vendor-neutral and modular way to deploy your App with `curl` from a CI/CD-Pipeline to your Container Cluster of
choice.

## Example

```shell
curl -sSf -H "Authorization: Bearer <secret>" -d "{
  \"resource\": \"my-app\",
  \"actions\": [{\"env\": {
    \"IMAGE\": \"ghcr.io/my/app:1.0.0\"
  }}]
}" http://my-kubedeploy:8080/deploy/<target-id>
```

## Actions

### Env

```json
{
  "env": {
    "IMAGE": "myimage:latest"
  }
}
```

### Yaml

```json
{
  "yaml": {
    "path": [
      "services",
      "test",
      "image"
    ],
    "value": "myimage:latest"
  }
}
```

### Json

```json
{
  "json": {
    "path": [
      "services",
      "test",
      "image"
    ],
    "value": "myimage:latest"
  }
}
```

### Regex

```json
{
  "regex": {
    "find": "(?<=image: ).*?(?=\\r?\\n|$)",
    "replace": "myimage:latest"
  }
}
```

### Stack Config

```yaml
version: '3.8'

services:
  kubedeploy:
    image: ghcr.io/lhns/kubedeploy:latest
    environment:
      CONFIG: |
        {
          "targets": [
            {
              "id": "<target-id>",
              "secret": "<secret>",
              "portainer": {
                "url": "http://my-portainer",
                "username": "<username>",
                "password": "<password>"
              }
            }
          ]
        }
    ports:
      - "8080:8080"
```

## Supported

- Docker Swarm via Portainer API
  - doesn't support deployment status yet
- GitOps
  - doesn't support deployment status yet (via Argo CD, Flux or Kubernetes API)

## Planned

- Docker Swarm via Docker API
- Kubernetes API

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
