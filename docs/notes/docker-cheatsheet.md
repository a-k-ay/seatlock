## The Mental Model
- **Image** = frozen template (like a class).
- **Container** = running instance of an image (like an object).
- **Volume** = persistent storage that survives container restarts.
## Docker Compose Basics
`docker-compose.yml` describes multi-container setups. Standard for local dev.
```yaml
services:
  postgres:
    image: postgres:16
    container_name: myapp-postgres
    environment:
      POSTGRES_DB: myapp
      POSTGRES_USER: myapp
      POSTGRES_PASSWORD: myapp
    ports:
      - "5432:5432"        # host:container
    volumes:
      - postgres_data:/var/lib/postgresql/data
volumes:
  postgres_data:
  
  
Common Commands

docker compose up -d              # start containers in background
docker compose down               # stop and remove containers (keeps volumes)
docker compose down -v            # also delete volumes (wipes data)
docker compose logs -f postgres   # follow logs of one service
docker compose restart postgres   # restart one service

docker ps                         # list running containers
docker ps -a                      # include stopped
docker exec -it CONTAINER bash    # shell into container
docker exec -it postgres-container psql -U myapp -d myapp

docker volume ls                  # list volumes
docker volume rm VOLUME_NAME      # delete a specific volume

docker images                     # list images
docker image prune                # cleanup unused images
docker system prune -a            # nuclear cleanup (careful)

Common Gotchas

Env vars only apply on FIRST container init. Changing POSTGRES_PASSWORD after volume exists doesn't work — you must docker volume rm and recreate.
Port conflicts: if 5432 is in use, map to 5433:5432 and update your app's connection string.
localhost inside a container ≠ your machine's localhost. Use container name (postgres) or host.docker.internal for host access.
Volume deletion is permanent. Data gone. Always check what you're deleting.
  
