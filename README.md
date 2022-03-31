# kubedeploy

[![Docker Workflow](https://github.com/LolHens/kubedeploy/workflows/build/badge.svg)](https://github.com/LolHens/kubedeploy/actions?query=workflow%3Abuild)
[![Release Notes](https://img.shields.io/github/release/LolHens/kubedeploy.svg?maxAge=3600)](https://github.com/LolHens/kubedeploy/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/kubedeploy.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

A vendor-neutral and modular way to deploy your App with `curl` from a CI/CD-Pipeline to your Container Cluster of
choice.

## Example

```shell
curl -sSf -H "Authorization: Bearer <secret>" -d "{
  \"resource\": \"my-app\",
  \"value\": \"ghcr.io/my/app:1.0.0\"
}" http://my-kubedeploy:8080/deploy/<target-id>
```

### Stack Config

```yaml
version: '3.8'

services:
  kubedeploy:
    image: ghcr.io/lolhens/kubedeploy:latest
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

## Planned

- Docker Swarm via Docker API
- GitOps (Argo CD, Flux)
- Kubernetes API

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
