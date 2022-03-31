# kubedeploy

A vendor-neutral and modular way to deploy your App with `curl` from a CI/CD-Pipeline to your Container Cluster of
choice.

## Example

```shell
curl -sSf -H "Authorization: Bearer <secret>" -d "{
  \"resource\": \"my-app\",
  \"value\": \"ghcr.io/my/app:1.0.0\"
}" http://localhost:8080/deploy/<target>
```

## Supported

- Docker Swarm via Portainer API
  - doesn't support deployment status yet

## Planned

- Docker Swarm via Docker API
- GitOps (Argo CD, Flux)
- Kubernetes API
