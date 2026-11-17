Build et déploie l'application sur le LXC dev (192.168.20.78).

1. Build front Vue : `cd front && npm run build`
   - Si le build échoue, s'arrêter et rapporter -- ne pas continuer.
2. Build Maven avec tests : `mvn -f java/pom.xml -pl web,worker,mise-a-jour -am clean package`
   - Si le build ou les tests échouent, s'arrêter et rapporter -- ne pas déployer.
3. Livrer les scripts d'exploitation (liste unique `scripts/liste-exploitation.txt`, contrôle de dérive compris) puis les JARs vers les fichiers temporaires (déploiement atomique) :
   ```
   SSH=ssh SCP=scp HOST=root@192.168.20.78
   . scripts/deposer-exploitation.sh && deposer_exploitation
   scp java/web/target/web-*.jar root@192.168.20.78:/opt/strapontin/application/web.jar.new
   scp java/worker/target/worker-*.jar root@192.168.20.78:/opt/strapontin/application/worker.jar.new
   scp java/mise-a-jour/target/mise-a-jour-*.jar root@192.168.20.78:/opt/strapontin/application/mise-a-jour.jar.new
   ```
4. Redémarrer les services : `ssh root@192.168.20.78 "/opt/strapontin/exploitation/update.sh"`
   - update.sh vérifie la livraison contre le manifeste, aligne unités et configuration AIDE sur le dépôt (chaque écart est signalé), fait le mv atomique, sauvegarde .prev et rollback automatique en cas d'échec.
5. Surveiller le démarrage : lire les journaux `strapontin-web` pendant 10 secondes via
   `ssh root@192.168.20.78 "journalctl -u strapontin-web -n 30 --no-pager"`
   Signaler toute erreur ou exception au démarrage.
