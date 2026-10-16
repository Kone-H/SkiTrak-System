# SkiTrak — CS6650 Assignment 1

A swipe-ingest server (REST + WebSocket, one WAR on Tomcat 9 / AWS EC2) and a multithreaded load client that sends 500,000 lift-swipe events while 10 WebSocket subscribers watch the live leaderboard.

```
SkiTrak-System/
├── server/         # SkierServlet (POST /skiers/...), HealthServlet (GET /health),
│                   # LeaderboardEndpoint (WS /leaderboard/{resortID})  → packaged as one WAR
├── client-part1/   # load client: warmup + main phase, HTTP ingest + WebSocket subscribe paths
├── client-part2/   # client-part1 + per-swipe latency CSV, statistics, throughput buckets
├── results/        # CSV output of client-part2
└── docs/           # diagrams and design doc
```

---

## 0. Before you start — fill in your own values

Every command below uses two shell variables. **Set them once in each new terminal tab** (on your Mac), replacing the values with your own:

```bash
export KEY=~/path/to/your-key.pem
```

```bash
export EC2_IP=[Your_IP]
```



> ⚠️ **The public IP changes every time the instance is stopped and started** (AWS Academy Learner Lab stops it at the end of every session). Re-copy it after each *Start Lab*, and update it everywhere it is used: the `EC2_IP` variable, Postman, and the IntelliJ run configuration (Section 6).

**Requirements**

| Where | Software |
|---|---|
| Mac (local) | JDK 11 or newer, Maven 3.x, IntelliJ IDEA, `curl`; optional: `wscat` (`npm install -g wscat`) |
| EC2 | Amazon Linux, Java 11, Tomcat 9 installed in `/opt/tomcat9` (Lab 1), region **us-west-2** |
| Security group | Inbound **TCP 22** (SSH) and **TCP 8080** (Tomcat), source = **My IP** (not `0.0.0.0/0`) |

Labels used below: **[Mac]** = run in a terminal on your own computer. **[EC2]** = run inside the SSH session on the instance.

---

## 1. Package the server into a WAR file

The server project is a Maven project with `<packaging>war</packaging>`, so Maven builds the WAR for you.

**Option A — command line [Mac]**

```bash
cd ~/Desktop/6650/SkiTrak-System/server
```

```bash
mvn clean package
```

- `clean` deletes the old `target/` folder, so no stale classes end up in the WAR.
- `package` compiles every class and bundles them, plus `WEB-INF/web.xml`, into a WAR.
- Wait for **`BUILD SUCCESS`**. The file is created at:

```
~/Desktop/6650/SkiTrak-System/server/target/ski-servlet-1.0-SNAPSHOT.war
```

**Option B — IntelliJ:** open the `server` project → right sidebar **Maven** (`m` icon) → **ski-servlet → Lifecycle** → double-click **clean**, then double-click **package**.

**Verify the WAR contains all classes (optional) [Mac]**

```bash
unzip -l ~/Desktop/6650/SkiTrak-System/server/target/ski-servlet-1.0-SNAPSHOT.war | grep class
```

Expected: `HealthServlet.class`, `LeaderboardEndpoint.class`, `SkierServlet.class`, `model/LiftRide.class`, `model/ResponseMsg.class`, all under `WEB-INF/classes/`.

> ⚠️ After **any** server code change you must re-package and re-upload. EC2 runs the WAR you uploaded, not the code in IntelliJ.

---

## 2. Connect to EC2

1. AWS Academy → **Modules → Launch AWS Academy Learner Lab** → **Start Lab**, and wait until the dot turns **green**.
2. Open the AWS Console → **EC2 → Instances**. If the instance is *Stopped*: **Instance state → Start instance**, and wait for *Running*.
3. Copy the **Public IPv4 address** and set `EC2_IP`.
4. **Check that your current IP is allowed [Mac]:**


5. **Protect the key file (first time only) [Mac].** SSH refuses keys that other users can read.

```bash
chmod 400 $KEY
```

6. **Connect [Mac]:**

```bash
ssh -i $KEY ec2-user@$EC2_IP
```

   - Type `yes` the first time you connect to a new IP.
   - Success: the prompt becomes `[ec2-user@ip-xxx-xxx-xxx-xxx ~]$`.
   - Leave with `exit` (or `Control + D`). Exiting SSH does **not** stop Tomcat.

> 💡 Keep two terminal tabs open (`⌘ T`): one for the SSH session on EC2, one on the Mac. Remember to run the two `export` lines again in each new Mac tab.

---

## 3. Upload the WAR file to EC2

**[Mac]** — use absolute paths, so the command works from any folder:

```bash
scp -i $KEY ~/Desktop/6650/SkiTrak-System/server/target/ski-servlet-1.0-SNAPSHOT.war ec2-user@$EC2_IP:/tmp/
```

- Wait for the progress bar to reach **100%**.
- Check that the file arrived **[EC2]**:

```bash
ls -la /tmp/ski-servlet-1.0-SNAPSHOT.war
```

> ⚠️ `/tmp` on the instance is cleared when the instance restarts. Deploy (Sections 4–5) right after uploading.

---

## 4. Rename the WAR and grant access (as in Lab 2, Part 4)

All commands are **[EC2]** and all need `sudo`, because `/opt/tomcat9/webapps/` may contain files owned by `root` from earlier deployments.

**Step 4.1 — Remove the previous deployment** (Lab 2 Part 4, "Clean Previous Deployments")

```bash
sudo rm -rf /opt/tomcat9/webapps/ski-servlet*
```

```bash
sudo rm -rf /opt/tomcat9/work/Catalina/localhost/ski-servlet*
```

The first command removes the old `ski-servlet.war` and the folder Tomcat unpacked from it. The second removes Tomcat's cached work files for the old version.


**Step 4.2 — Copy the new WAR into Tomcat's `webapps/` folder**

```bash
sudo cp /tmp/ski-servlet-1.0-SNAPSHOT.war /opt/tomcat9/webapps/
```

**Step 4.3 — Rename it to `ski-servlet.war`**

Tomcat uses the WAR's file name as the URL context path. After renaming, the URL becomes `/ski-servlet/...` instead of `/ski-servlet-1.0-SNAPSHOT/...`.

```bash
sudo mv /opt/tomcat9/webapps/ski-servlet-1.0-SNAPSHOT.war /opt/tomcat9/webapps/ski-servlet.war
```

**Step 4.4 — Grant access: make the Tomcat user the owner**

```bash
sudo chown ec2-user:ec2-user /opt/tomcat9/webapps/ski-servlet.war
```

> 💡 The lab handout writes `sudo chown tomcat:tomcat …`. The goal is "owner = the user that runs Tomcat". On this instance that user is **`ec2-user`**: Lab 1 made `ec2-user` the owner of `/opt/tomcat9`, and Tomcat is started by `ec2-user` with `startup.sh`. There is no `tomcat` user, so `tomcat:tomcat` fails with `invalid user`.

**Check the result:**

```bash
ls -la /opt/tomcat9/webapps/
```

You should see `ski-servlet.war` owned by `ec2-user`. Within a few seconds a `ski-servlet/` folder appears next to it, if Tomcat is running.

---

## 5. Deploy the WAR on Tomcat and test it

### 5.1 Start Tomcat [EC2]

Tomcat was installed by hand in Lab 1 and does **not** start automatically after the instance reboots, so start it after every *Start Lab*:

```bash
/opt/tomcat9/bin/startup.sh
```

Check that it is listening on port 8080. You should see a line with `LISTEN … *:8080 … java`:

```bash
sudo ss -tlnp | grep 8080
```

> 💡 `systemctl status tomcat9` reports *"could not be found"*. That is expected, because Tomcat is not registered as a system service. Use the `ss` command above instead.





### 5.3 Test the three endpoints [Mac]

**Health check** → expect `HTTP/1.1 200`

```bash
curl -i http://$EC2_IP:8080/ski-servlet/health
```

**Swipe ingest** → expect `HTTP/1.1 201`

```bash
curl -i -X POST "http://$EC2_IP:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123" -H "Content-Type: application/json" -d '{"time": 217, "liftID": 21}'
```

**Validation** → expect `HTTP/1.1 400` (`liftID` out of the range 1–40)

```bash
curl -i -X POST "http://$EC2_IP:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123" -H "Content-Type: application/json" -d '{"time": 217, "liftID": 99}'
```

**WebSocket leaderboard** → expect one JSON message every 500 ms with `resortID`, `serverTimestamp` and `top`. Press `Control + C` to quit.

```bash
wscat -c ws://$EC2_IP:8080/ski-servlet/leaderboard/7
```

### 5.4 Stop Tomcat (when needed) [EC2]

```bash
/opt/tomcat9/bin/shutdown.sh
```

At the end of the work session: AWS Academy → **End Lab**.

---

## 6. Run the load client from IntelliJ

Both clients have the same entry point, **`LoadClientMsg`**, and the same program arguments:

```
<baseUrl> [mainThreads]
```

| Argument | Meaning | Example | Default if omitted |
|---|---|---|---|
| `baseUrl` | Server URL **including the WAR context path**, **no trailing slash** | `http://[Your_IP]:8080/ski-servlet` | `http://localhost:8080/ski-servlet` |
| `mainThreads` | Number of sender threads in the **main** phase (the warmup always uses 32 threads × 1,000 swipes) | `64` | `128` |

The WebSocket URL is derived from `baseUrl` automatically (`ws://…/leaderboard/{resortID}` for resorts 1–10).

### 6.1 Create the run configuration (client-part1)

1. **File → Open** → select `~/Desktop/6650/SkiTrak-System/client-part1` → *Open as Project*. Wait for Maven to finish importing (bottom-right progress bar). This downloads **Tyrus** (WebSocket client) and **Gson**.
2. **Run → Edit Configurations…** → **`+`** (top-left) → **Application**.
3. Fill in:

| Field | Value |
|---|---|
| **Name** | `LoadClientMsg` |
| **Build and run → JDK/JRE** | JDK 11 or newer |
| **`-cp` (module)** | `client-part1` |
| **Main class** | `LoadClientMsg` |
| **Program arguments** | `http://[Your_IP]:8080/ski-servlet 64` |
| **Working directory** | `~/Desktop/6650/SkiTrak-System/client-part1` (shown as `$MODULE_WORKING_DIR$`) |

   If *Program arguments* or *Working directory* is not visible: click **Modify options** and enable them.
4. **Apply → OK**, then click the green **▶ Run** button. Before starting, IntelliJ compiles the code (*Build → Make* runs first).

### 6.2 client-part2

Repeat 6.1 with `client-part2` as the project and module. **The working directory matters here:** the CSV files are written to `../results/`, relative to the working directory. With the working directory set to `…/SkiTrak-System/client-part2`, they land in the repository's `results/` folder:

```
SkiTrak-System/results/swipes_<mainThreads>threads.csv       # one row per swipe: timestamp,requestType,latency,statusCode,resortID
SkiTrak-System/results/throughput_<mainThreads>threads.csv   # swipes/second in 10-second buckets (for the chart)
```

### 6.3 Trying different thread counts

Change only the second program argument (e.g. `… 32`, `… 64`, `… 128`, `… 256`) and run again. One full run sends 500,000 swipes and takes about **10–17 minutes** from a home network to `us-west-2`. Run configurations **one at a time**, because parallel runs share the network and distort each other's results.

### 6.4 What a successful run prints

1. **Warmup phase**: 32 threads, 32,000 swipes, wall time, throughput
2. **Main phase**: N threads, 468,000 swipes, wall time, throughput
3. **Little's Law**: predicted (N ÷ warmup latency) vs. actual throughput
4. **Overall**: 500,000 successful, 0 failed, total wall time, overall throughput
5. **Connection statistics** and the **subscribe path**: updates per resort, reconnections, WebSocket connect time, push-latency percentiles
6. *(client-part2 only)* ingest latency mean / median / p95 / p99 / min / max, per-resort throughput, and the CSV file paths

> 💡 Push latency (`receiveTime − serverTimestamp`) compares two different machines' clocks, so it includes **clock skew** and can be negative.

### 6.5 Optional: run without IntelliJ [Mac]

```bash
cd ~/Desktop/6650/SkiTrak-System/client-part1
```

```bash
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
```

```bash
java -cp "target/classes:$(cat cp.txt)" LoadClientMsg http://$EC2_IP:8080/ski-servlet 64
```

---

## Appendix A — Checklist at the start of every session

1. **Start Lab** → instance *Running* → copy the new public IP → `export EC2_IP=…`
2. `curl -4 ifconfig.me` matches the security-group source?
3. `ssh -i $KEY ec2-user@$EC2_IP` → `/opt/tomcat9/bin/startup.sh`
4. `curl -i http://$EC2_IP:8080/ski-servlet/health` returns `200`
5. Update the IP in the IntelliJ **Program arguments** (Section 6) and in Postman

## Appendix B — Redeploy after changing server code

1. [Mac] Section 1: `mvn clean package`
2. [Mac] Section 3: `scp` the WAR to `/tmp/`
3. [EC2] Section 4: `rm -rf` the old deployment → `cp` → `mv` → `chown`
4. [EC2] Section 5.2: watch `catalina.out` for `Deployment … has finished`
5. [Mac] Section 5.3: test with `curl` / `wscat`

## Appendix C — Common errors

| Symptom | Cause | Fix |
|---|---|---|
| `ssh` / `curl` hangs, then `Operation timed out` | Old EC2 IP, or your IP is not in the security group | Re-copy the IP (Section 2, step 3); update the inbound rules (step 4) |
| `Permission denied (publickey)` | Wrong key path, wrong user, or the key's permissions are too open | Use `ec2-user`; `chmod 400 $KEY` |
| `scp: … No such file or directory` | WAR not built, or relative path from the wrong folder | Run Section 1; use the absolute path as shown |
| `cp: cannot stat '/tmp/…'` | Upload failed, or the instance restarted and `/tmp` was cleared | Repeat Section 3 |
| `cp: … Permission denied` | Missing `sudo` | Repeat Section 4 with `sudo` on every command |
| `chown: invalid user: 'tomcat:tomcat'` | No `tomcat` user on this instance | Use `ec2-user:ec2-user` (Step 4.4) |
| `curl: Connection refused` | Tomcat is not running | Section 5.1 |
| `404` from `curl` | Wrong context path (`ski-servlet`, not `ski_servlet`), or deployment not finished | Check the URL; Section 5.2 |
| Client: all requests fail / `ConnectException` | Old IP in the IntelliJ program arguments, or Tomcat not running | Update Section 6 → *Program arguments*; check with the `/health` curl (5.3) |
| `tail -f` does not return to the prompt | It keeps following the log | `Control + C` |
