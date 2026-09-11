# DiscoveryHub — Kubernetes manifests (stretch goal, NFR-4)

These manifests deploy every backend service + the frontend to a local
Kubernetes cluster (Docker Desktop, minikube, or kind). They assume you have
already built and pushed the images to a registry your cluster can pull from
(or that you build them inside the cluster with e.g. `tilt`/`skaffold`).

> The primary, required deployment path is `docker compose up`. The manifests
> here are the stretch goal — "deploying at least one service to an
> orchestrated cluster".

> **Status: not yet deployed.** The manifests are syntactically valid and their
> configuration matches the current architecture, but they have not been
> applied to a running cluster. Treat them as a starting point, not as a
> verified deployment.

## Apply (against a cluster that can pull the images)

```bash
# Create the secret from your environment rather than committing values.
kubectl create secret generic discoveryhub-secrets \
  --from-literal=postgres.username="$POSTGRES_USER" \
  --from-literal=postgres.password="$POSTGRES_PASSWORD" \
  --from-literal=mongo.uri="$MONGODB_URI" \
  --from-literal=aws.access-key-id="$AWS_ACCESS_KEY_ID" \
  --from-literal=aws.secret-access-key="$AWS_SECRET_ACCESS_KEY"

kubectl apply -f infra/k8s/
```

Applying `config.yaml` as-is will overwrite that secret with empty demo values,
so either skip it or apply only its ConfigMap.

## Notes

- Each service is a `Deployment` + `ClusterIP` `Service`. The frontend is a
  `LoadBalancer` so you can reach it from the host.
- **Object storage is Amazon S3**, configured through `aws.*` keys in the
  ConfigMap and the AWS credentials in the Secret. Credentials are resolved by
  the AWS SDK's default provider chain, which reads `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY` from the environment.
  - **On EKS, do not use those keys at all.** Delete them from the Secret and
    the two Deployments, and attach an IAM role to the service account (IRSA).
    The same provider chain picks the role up automatically, so no long-lived
    credential exists.
  - To run fully in-cluster without AWS, deploy an S3-compatible server and set
    `aws.s3.endpoint` plus `aws.s3.path-style: "true"` — no code changes.
- Infrastructure (Postgres, MongoDB, Elasticsearch, Kafka) is expected to be
  reachable at the hostnames in the ConfigMap. For a true all-in-k8s setup, add
  StatefulSets for those; for the stretch goal you can point the services at
  the dockerized infra on the host by overriding those values.
- `aws.s3.auto-create-buckets` is `"false"`: the buckets are provisioned by
  infrastructure, and the application's IAM user normally has no
  `s3:CreateBucket` permission.
- A Kubernetes `Secret` is base64-encoded, **not encrypted**. Anyone with
  `get secret` rights on the namespace can read it. Use a real secret provider
  (External Secrets, Sealed Secrets, SSM/Secrets Manager) for anything beyond
  a demo.
