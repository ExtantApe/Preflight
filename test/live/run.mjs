// Preflight's live scenarios, on throwaway Paper servers on 127.0.0.1, one at a time, each deleted afterwards.
// Logins come from a mineflayer bot that only needs to get through the login step.
//
//   PF_TEST_JARS   folder with paper-<version>.jar (and, for 26.2+, ViaVersion-*.jar and ViaBackwards-*.jar)
//   PF_TEST_EULA   an eula.txt the owner already accepted. It's copied, never written.
//   PF_TEST_RUN    where servers are built. Must be under a temp folder.
//   PF_TEST_PAPER  26.1.2 (default), 26.2 or 26.3
//   PF_TEST_JAVA   a Java 25
//
// ./gradlew build, then: cd test/live && npm ci && node run.mjs
import { spawn } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import mineflayer from 'mineflayer'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/live/run.mjs)'); return v }
const JARS = must('PF_TEST_JARS'), EULA = must('PF_TEST_EULA'), RUN = path.resolve(must('PF_TEST_RUN'))
const PAPER = process.env.PF_TEST_PAPER ?? '26.1.2', JAVA = process.env.PF_TEST_JAVA ?? 'java', VIA = !PAPER.startsWith('26.1')
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].some(t => RUN.startsWith(path.resolve(t) + path.sep))) throw new Error('PF_TEST_RUN must be under a temp folder')
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error("That eula.txt isn't accepted. Accepting it is the owner's call.")
const ROOT = new URL('../../', import.meta.url).pathname
const built = (dir, re) => { const d = path.join(ROOT, dir, 'build/libs'), f = readdirSync(d).filter(n => re.test(n)).sort().at(-1); if (!f) throw new Error('Run ./gradlew build first: nothing like ' + re + ' in ' + d); return path.join(d, f) }
const version = readFileSync(path.join(ROOT, 'build.gradle.kts'), 'utf8').match(/version = "([^"]+)"/)[1]
const PREFLIGHT = built('preflight-plugin', new RegExp(`^Preflight-${version.replace(/\./g, '\\.')}\\.jar$`))
const EXAMPLE = built('preflight-example', new RegExp(`^PreflightExample-${version.replace(/\./g, '\\.')}\\.jar$`))
const FIXTURE = built('test/live/fixture', /^PfFixture-.*\.jar$/), DEPENDENT = built('test/live/dependent', /^PfDependent-.*\.jar$/)
const PORT = 25640
const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
function check(name, ok, detail = '') { results.push({ name, ok: !!ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail && !ok ? '  (' + String(detail).slice(0, 200) + ')' : ''}`) }

class Server {
  constructor(name, { fixture = 'pass', onFailure = 'LOCKDOWN', shutdownAfterRun = false, dependent = false, staleReport = false } = {}) {
    this.dir = path.join(RUN, `${name}-${PAPER}`)
    rmSync(this.dir, { recursive: true, force: true })
    mkdirSync(path.join(this.dir, 'plugins/Preflight'), { recursive: true })
    mkdirSync(path.join(this.dir, 'plugins/PfFixture'), { recursive: true })
    copyFileSync(path.join(JARS, `paper-${PAPER}.jar`), path.join(this.dir, 'paper.jar'))
    copyFileSync(EULA, path.join(this.dir, 'eula.txt'))
    for (const j of [PREFLIGHT, EXAMPLE, FIXTURE, ...(dependent ? [DEPENDENT] : [])]) copyFileSync(j, path.join(this.dir, 'plugins', path.basename(j)))
    if (VIA) for (const j of readdirSync(JARS).filter(n => /^Via(Version|Backwards)-.*\.jar$/.test(n))) copyFileSync(path.join(JARS, j), path.join(this.dir, 'plugins', j))
    writeFileSync(path.join(this.dir, 'plugins/PfFixture/mode.txt'), fixture)
    writeFileSync(path.join(this.dir, 'plugins/Preflight/config.yml'), `enabled: true\non-failure: ${onFailure}\nshutdown-after-run: ${shutdownAfterRun}\nskip-tests: []\n`)
    if (staleReport) writeFileSync(path.join(this.dir, 'plugins/Preflight/report.xml'), '<testsuites><!-- stale from an earlier run --></testsuites>\n')
    writeFileSync(path.join(this.dir, 'server.properties'), [`server-ip=127.0.0.1`, `server-port=${PORT}`, 'online-mode=false', 'level-type=minecraft\\:flat',
      'generate-structures=false', 'view-distance=4', 'simulation-distance=4', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false'].join('\n') + '\n')
    this.log = ''
  }
  start() {
    this.proc = spawn(JAVA, ['-Xms1G', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: this.dir })
    this.exited = new Promise(r => this.proc.on('exit', code => { this.code = code; r(code) }))
    for (const s of [this.proc.stdout, this.proc.stderr]) s.on('data', d => { this.log += d })
  }
  send(c) { if (this.code === undefined) this.proc.stdin.write(c + '\n') }
  async waitFor(re, ms = 120000) {
    const end = Date.now() + ms
    while (Date.now() < end) { const m = this.log.match(re); if (m) return m; if (this.code !== undefined) return null; await sleep(250) }
    return null
  }
  report() { const f = path.join(this.dir, 'plugins/Preflight/report.xml'); return existsSync(f) ? readFileSync(f, 'utf8') : null }
  async stop() {
    if (this.code === undefined) { this.send('stop'); await Promise.race([this.exited, sleep(30000)]) }
    if (this.code === undefined) this.proc.kill('SIGKILL')
    await sleep(500)
    rmSync(this.dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
  }
}

/** Tries to log in. Resolves to "in" or the kick reason. */
function login(name) {
  return new Promise(resolve => {
    const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '26.1', auth: 'offline', hideErrors: true, physicsEnabled: false })
    const done = r => { try { b.quit() } catch {} resolve(r) }
    b.once('spawn', () => done('in'))
    b.once('kicked', r => done(typeof r === 'string' ? r : JSON.stringify(r)))
    b.once('end', r => done('ended: ' + r))
    b.on('error', () => {})
    setTimeout(() => done('timeout'), 30000)
  })
}

async function scenario(name, opts, body) {
  console.log(`\n# ${name} (Paper ${PAPER})`)
  const s = new Server(name.toLowerCase().replace(/[^a-z]+/g, '-'), opts)
  try { s.start(); await body(s) } catch (e) { check(`${name}: ran to the end`, false, e.message) } finally { await s.stop() }
}

await scenario('Clean run', { shutdownAfterRun: true, staleReport: true }, async s => {
  const code = await Promise.race([s.exited, sleep(180000).then(() => 'timeout')])
  const r = s.report() ?? ''
  check('the server stops itself after the run', code !== 'timeout', String(code))
  check('the report has no failures, and the stale one is gone', /failures="0"/.test(r) && !/stale from an earlier run/.test(r), r.slice(0, 200))
  check("the example plugin's tests ran", /PreflightExample/.test(r))
  const leftover = readdirSync(s.dir, { recursive: true }).map(String).filter(n => /preflight_test/.test(n))
  check('the test world is deleted', leftover.length === 0, leftover.slice(0, 3).join(', '))
})

await scenario('Logins held', { fixture: 'slow', staleReport: true }, async s => {
  await s.waitFor(/Running \d+ startup tests/)
  check('while the tests run, the old report is already gone', s.report() === null)
  const during = await login('Early')
  check('a login during the tests is refused, and told why', /still running startup checks/.test(during), during)
  const summary = await s.waitFor(/(\d+)\/(\d+) startup tests passed/, 120000)
  check('the slow test finishes and every test passes', summary && summary[1] === summary[2], summary?.[0])
  await sleep(1000)
  const after = await login('Later')
  check('once they pass, the same login works', after === 'in', after)
})

await scenario('Lockdown', { fixture: 'throw' }, async s => {
  await s.waitFor(/Logins are refused until an admin runs \/preflight unlock/)
  check('a plugin that fails to enable locks the server', /critical startup test\(s\) failed\. Action: LOCKDOWN/.test(s.log))
  const locked = await login('Locked')
  check('a login is refused while locked, and told why', /locked for maintenance/.test(locked), locked)
  check('the report names the plugin that failed', /PfFixture/.test(s.report() ?? ''))
  s.send('preflight unlock')
  await s.waitFor(/Lockdown lifted/, 10000)
  const after = await login('Unlocked')
  check('after /preflight unlock, logins work', after === 'in', after)
})

await scenario('Disable plugin', { fixture: 'fail', onFailure: 'DISABLE_PLUGIN', dependent: true }, async s => {
  await s.waitFor(/Disabling PfFixture after failed startup tests/)
  await sleep(1500)
  const a = s.log.indexOf('Disabling PfDependent after failed'), b = s.log.indexOf('Disabling PfFixture after failed')
  check('the plugin that needs the failing one (declared in paper-plugin.yml) is disabled first', a >= 0 && b > a, `${a} ${b}`)
  check('it really stopped', /PfDependent disabled/.test(s.log))
  const open = await login('Open')
  check('with no server-level failure, logins are open', open === 'in', open)
})

await scenario('Shutdown', { fixture: 'fail', onFailure: 'SHUTDOWN' }, async s => {
  const code = await Promise.race([s.exited, sleep(180000).then(() => 'timeout')])
  check('a critical failure stops the server', code !== 'timeout', String(code))
  check('the report records the failure', /failures="[1-9]/.test(s.report() ?? ''), (s.report() ?? 'no report').slice(0, 200))
})

const failed = results.filter(r => !r.ok).length
console.log(`\n${results.length - failed}/${results.length} passed on Paper ${PAPER}`)
process.exit(failed ? 1 : 0)
