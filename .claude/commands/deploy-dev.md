Build et déploie l'application sur le LXC dev (192.168.20.78).

1. Build front Vue : `cd front && npm run build`
   - Si le build échoue, s'arrêter et rapporter -- ne pas continuer.
2. Build Maven avec tests : `mvn -f java/pom.xml -pl web,worker -am clean package`
   - Si le build ou les tests échouent, s'arrêter et rapporter -- ne pas déployer.
3. Copier les scripts déployés (mêmes fichiers que `scripts/woodpecker-deploy.sh`) puis les JARs vers les fichiers temporaires (déploiement atomique) :
   ```
   scp scripts/backup.sh scripts/restore.sh scripts/update.sh scripts/rotation-secrets.sh scripts/declencher-job.sh root@192.168.20.78:/opt/strapontin/scripts/
   ssh root@192.168.20.78 "chmod +x /opt/strapontin/scripts/*.sh"
   scp java/web/target/web-*.jar root@192.168.20.78:/opt/strapontin/web.jar.new
   scp java/worker/target/worker-*.jar root@192.168.20.78:/opt/strapontin/worker.jar.new
   ```
4. Redémarrer les services : `ssh root@192.168.20.78 "/opt/strapontin/scripts/update.sh"`
   - update.sh fait le mv atomique, sauvegarde .prev et rollback automatique en cas d'échec.
5. Surveiller le démarrage : lire les journaux `strapontin-web` pendant 10 secondes via
   `ssh root@192.168.20.78 "journalctl -u strapontin-web -n 30 --no-pager"`
   Signaler toute erreur ou exception au démarrage.
