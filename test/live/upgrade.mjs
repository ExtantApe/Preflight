// preflight-upgrade's live checks: the real CLI jar against a made-up server folder that a real Paper jar has
// booted, with copies booted on 127.0.0.1 by the tool itself. One Paper process at a time. Everything is built in
// a fresh temp folder, which is deleted when the run ends, pass or fail.
//
//   PF_TEST_JARS   folder with paper-<version>.jar
//   PF_TEST_EULA   an eula.txt the owner already accepted. It's copied, never written.
//   PF_TEST_PAPER  the version to check against (--paper): 26.1.2 (default), 26.2 or 26.3
//   PF_TEST_FROM   the version the made-up server is made with (default 26.1.2)
//   PF_TEST_JAVA   a Java 25
//   PF_TEST_TMP    optional parent for the temp folder (default: the system temp folder)
//
// ./gradlew build, then: node test/live/upgrade.mjs
import { execFileSync, spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  appendFileSync, chmodSync, copyFileSync, cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync,
  readdirSync, realpathSync, renameSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync,
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/live/upgrade.mjs)'); return v }
const JARS = must('PF_TEST_JARS'), EULA = must('PF_TEST_EULA')
const PAPER = process.env.PF_TEST_PAPER ?? '26.1.2', FROM = process.env.PF_TEST_FROM ?? '26.1.2', JAVA = process.env.PF_TEST_JAVA ?? 'java'
const JAR_TOOL = JAVA.includes('/') ? path.join(path.dirname(JAVA), 'jar') : 'jar'
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error("That eula.txt isn't accepted. Accepting it is the owner's call.")
const TMP = realpathSync(process.env.PF_TEST_TMP ?? os.tmpdir())
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders', '/private/var/folders'].some(t => TMP === realpathSync(t) || TMP.startsWith(realpathSync(t) + path.sep))) throw new Error('PF_TEST_TMP must be a temp folder')

const ROOT = new URL('../../', import.meta.url).pathname
const built = (dir, re) => { const d = path.join(ROOT, dir, 'build/libs'), f = existsSync(d) && readdirSync(d).filter(n => re.test(n)).sort().at(-1); if (!f) throw new Error('Run ./gradlew build first: nothing like ' + re + ' in ' + d); return path.join(d, f) }
const version = readFileSync(path.join(ROOT, 'build.gradle.kts'), 'utf8').match(/version = "([^"]+)"/)[1]
const v = version.replace(/\./g, '\\.')
const UPGRADE = built('preflight-upgrade', new RegExp(`^preflight-upgrade-${v}\\.jar$`))
const GOOD = [built('preflight-example', new RegExp(`^PreflightExample-${v}\\.jar$`)), built('test/live/fixture', /^PfFixture-.*\.jar$/), built('test/live/dependent', /^PfDependent-.*\.jar$/)]
const BROKEN = ['PfBadApi', 'PfNoMain', 'PfNeedsBad', 'PfChainEnd'].map(n => built('test/live/upgrade-fixtures', new RegExp(`^${n}\\.jar$`)))
const HOLD_LOCK = path.join(ROOT, 'test/live/HoldLock.java')
const PORT = 25641
const sleep = ms => new Promise(r => setTimeout(r, ms))

// ---- the temp folder, and making sure it goes

const RUN = realpathSync(mkdtempSync(path.join(TMP, 'pf-upgrade-live-')))
const SERVER = path.join(RUN, 'server'), ELSEWHERE = path.join(RUN, 'linked'), WORK = path.join(RUN, 'work')
const children = new Set()
let cleaned = false
function cleanUp() {
  if (cleaned) return
  cleaned = true
  for (const c of children) { try { c.kill('SIGKILL') } catch {} }
  // Every Paper process this run starts, including the tool's own copies, has RUN in its command line.
  try { execFileSync('pkill', ['-9', '-f', RUN], { stdio: 'ignore' }) } catch {}
  try { chmodSync(path.join(SERVER, 'plugins/Preflight/upgrade/rules.json'), 0o644) } catch {}
  try { rmSync(RUN, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 }) } catch (e) { console.error('Could not delete ' + RUN + ': ' + e.message) }
  if (existsSync(RUN)) { console.error('LEFT BEHIND: ' + RUN + ' (delete it by hand)'); process.exitCode = 3 }
  else console.log('Deleted ' + RUN)
}
process.on('exit', cleanUp)
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => process.exit(130))
process.on('uncaughtException', e => { console.error(e); process.exit(1) })

const results = []
function check(name, ok, detail = '') { results.push({ name, ok: !!ok }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail && !ok ? '  (' + String(detail).replace(/\s+/g, ' ').slice(0, 300) + ')' : ''}`) }

// ---- helpers

const sha = file => createHash('sha256').update(readFileSync(file)).digest('hex')

/** Every file under a folder, links followed, as relative path -> sha256. Folders show as "dir". */
function tree(dir, base = dir, out = {}) {
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name), s = statSync(p), rel = path.relative(base, p)
    if (s.isDirectory()) { out[rel + '/'] = 'dir'; tree(p, base, out) } else { try { out[rel] = sha(p) } catch (e) { out[rel] = e.code } }
  }
  return out
}
function sameTree(a, b) {
  const diff = [...new Set([...Object.keys(a), ...Object.keys(b)])].filter(k => a[k] !== b[k])
  return { same: diff.length === 0, diff: diff.slice(0, 6).join(', ') }
}
const worldFiles = () => Object.fromEntries(Object.entries(tree(SERVER)).filter(([k]) => /(^|\/)level\.dat$|\.mca$/.test(k)))
const copies = () => readdirSync(RUN).concat(existsSync(WORK) ? readdirSync(WORK) : []).filter(n => n.includes('.preflight-check-'))

/** Runs the real CLI jar. stdin is closed unless `input` is given. */
function cli(args, { input, watch } = {}) {
  return new Promise(resolve => {
    const p = spawn(JAVA, ['-jar', UPGRADE, ...args], { stdio: ['pipe', 'pipe', 'pipe'] })
    children.add(p)
    let out = '', err = ''
    p.stdout.on('data', d => { out += d })
    p.stderr.on('data', d => { err += d })
    if (input !== undefined) p.stdin.write(input)
    p.stdin.end()
    const timer = watch ? setInterval(watch, 250) : null
    p.on('exit', code => { children.delete(p); if (timer) clearInterval(timer); resolve({ code, out, err }) })
  })
}
const parse = text => { try { return JSON.parse(text) } catch { return null } }
const names = list => (list ?? []).map(x => x.name).sort()

/** A Paper process on the made-up server itself, for making its worlds and for the "running" refusals. */
class RealServer {
  start(jar) {
    this.log = ''
    this.proc = spawn(JAVA, ['-Xms1G', '-Xmx2G', '-jar', jar, '--nogui'], { cwd: SERVER })
    children.add(this.proc)
    this.exited = new Promise(r => this.proc.on('exit', code => { this.code = code; children.delete(this.proc); r(code) }))
    for (const s of [this.proc.stdout, this.proc.stderr]) s.on('data', d => { this.log += d })
  }
  async waitFor(re, ms = 180000) {
    const end = Date.now() + ms
    while (Date.now() < end) { const m = this.log.match(re); if (m) return m; if (this.code !== undefined) return null; await sleep(250) }
    return null
  }
  async stop() {
    if (this.code === undefined) { this.proc.stdin.write('stop\n'); await Promise.race([this.exited, sleep(60000)]) }
    if (this.code === undefined) { this.proc.kill('SIGKILL'); await this.exited }
  }
}

async function scenario(name, body) {
  console.log(`\n# ${name} (made with Paper ${FROM}, checked on Paper ${PAPER})`)
  try { await body() } catch (e) { check(`${name}: ran to the end`, false, e.stack) }
}

const setPlugins = jars => {
  for (const f of readdirSync(path.join(SERVER, 'plugins'))) if (/\.jar(\.disabled)?$/.test(f)) unlinkSync(path.join(SERVER, 'plugins', f))
  for (const j of jars) copyFileSync(j, path.join(SERVER, 'plugins', path.basename(j)))
}
const checkArgs = (extra = []) => ['check', '--server', SERVER, '--paper', TARGET, '--json', '--memory', '2G', '--timeout', '300', ...extra]

// ---- the made-up server

mkdirSync(path.join(RUN, 'jars'))
const TARGET = path.join(RUN, 'jars', `paper-${PAPER}.jar`), MAKER = path.join(RUN, 'jars', `paper-${FROM}-maker.jar`)
copyFileSync(path.join(JARS, `paper-${PAPER}.jar`), TARGET)
copyFileSync(path.join(JARS, `paper-${FROM}.jar`), MAKER)
mkdirSync(path.join(SERVER, 'plugins/PfFixture'), { recursive: true })
copyFileSync(EULA, path.join(SERVER, 'eula.txt'))
writeFileSync(path.join(SERVER, 'server.properties'), [`server-ip=127.0.0.1`, `server-port=${PORT}`, 'online-mode=false',
  'level-type=minecraft\\:flat', 'generate-structures=false', 'view-distance=4', 'simulation-distance=4', 'enable-query=false', 'enable-rcon=false'].join('\n') + '\n')

// A non-Paper jar (a plugin), and a jar shaped like a Paper 1.21.4 paperclip, made here from nothing.
const NOT_PAPER = GOOD[1]
const OLD_PAPER = path.join(RUN, 'jars', 'paper-1.21.4-fake.jar')
{
  const d = path.join(RUN, 'fake-old-paper')
  mkdirSync(path.join(d, 'META-INF'), { recursive: true })
  writeFileSync(path.join(d, 'manifest.txt'), 'Main-Class: io.papermc.paperclip.Main\n')
  writeFileSync(path.join(d, 'META-INF/versions.list'), '0000000000000000000000000000000000000000000000000000000000000000\t1.21.4\t1.21.4/paper-1.21.4.jar\n')
  execFileSync(JAR_TOOL, ['--create', '--file', OLD_PAPER, '--manifest', path.join(d, 'manifest.txt'), '-C', d, 'META-INF/versions.list'])
}

await scenario('Setup', async () => {
  const s = new RealServer()
  s.start(MAKER)
  const done = await s.waitFor(/Done \(/)
  await s.stop()
  check(`a made-up server boots once on Paper ${FROM} to make its worlds, and stops`, done && s.code !== undefined, s.log.slice(-300))
  // Link worlds in from elsewhere: a whole world folder, and one level.dat by itself.
  const worlds = readdirSync(SERVER).filter(n => existsSync(path.join(SERVER, n, 'level.dat'))).sort()
  check('the server has worlds with level.dat and region files', worlds.length >= 1 && Object.keys(worldFiles()).some(k => k.endsWith('.mca')), worlds.join(', '))
  mkdirSync(ELSEWHERE)
  renameSync(path.join(SERVER, worlds[0]), path.join(ELSEWHERE, worlds[0]))
  symlinkSync(path.join(ELSEWHERE, worlds[0]), path.join(SERVER, worlds[0]))
  if (worlds[1]) {
    renameSync(path.join(SERVER, worlds[1], 'level.dat'), path.join(ELSEWHERE, worlds[1] + '-level.dat'))
    symlinkSync(path.join(ELSEWHERE, worlds[1] + '-level.dat'), path.join(SERVER, worlds[1], 'level.dat'))
  }
  check('a world folder (and a second world\'s level.dat) are links to files outside the server', lstatSync(path.join(SERVER, worlds[0])).isSymbolicLink())
})

// ---- 1. Good plugins: all clear, and nothing touched

let worldsBefore
await scenario('All clear', async () => {
  setPlugins(GOOD)
  worldsBefore = worldFiles()
  const before = tree(SERVER)
  let sawCopy = false, sawLinkInCopy = false, sawRealWorldInCopy = false
  const world0 = readdirSync(ELSEWHERE).find(n => !n.endsWith('.dat'))
  const r = await cli(checkArgs(), {
    watch: () => {
      for (const c of copies()) {
        sawCopy = true
        try { const s = lstatSync(path.join(RUN, c, world0)); if (s.isSymbolicLink()) sawLinkInCopy = true; else if (s.isDirectory()) sawRealWorldInCopy = true } catch {}
      }
    },
  })
  const plan = parse(r.out)
  check('check exits 0 when every plugin starts', r.code === 0, `exit ${r.code}: ${r.err.slice(-300)}`)
  check('the plan says all-clear, with nothing to switch off or blocked', plan?.status === 'all-clear' && plan.off.length === 0 && plan.blocked.length === 0, r.out.slice(0, 300))
  check('the copy was made next to the server, and is deleted afterwards', sawCopy && copies().length === 0, copies().join(', '))
  check('in the copy, the linked world is a real folder, not a link', sawRealWorldInCopy && !sawLinkInCopy, `real ${sawRealWorldInCopy}, link ${sawLinkInCopy}`)
  const w = sameTree(worldsBefore, worldFiles())
  check('the real worlds\' region files and level.dat are byte-for-byte unchanged (sha256)', w.same && Object.keys(worldsBefore).length > 0, w.diff)
  const t = sameTree(before, tree(SERVER))
  check('nothing in the real server folder changed', t.same, t.diff)
  check('progress went to stderr, and stdout is only the JSON plan', plan !== null && /Copying the server/.test(r.err) && !/Copying/.test(r.out))
  // JSON shape, as docs/upgrade.md describes it.
  const keys = ['format', 'tool', 'server', 'checkedAt', 'target', 'status', 'off', 'blocked', 'notChecked', 'warnings', 'recheck', 'preflight']
  check('the plan has every documented key', plan && keys.every(k => k in plan), plan && keys.filter(k => !(k in plan)).join(', '))
  check('format 1, this tool\'s version, the server\'s real path, an ISO time', plan?.format === 1 && plan.tool === version && plan.server === SERVER && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(plan.checkedAt), JSON.stringify([plan?.format, plan?.tool, plan?.server, plan?.checkedAt]))
  const tgt = plan?.target ?? {}
  check(`target names the jar, Paper, Minecraft ${PAPER}, a build number and the version line`, tgt.file === path.basename(TARGET) && tgt.brand === 'Paper' && tgt.minecraft === PAPER && Number.isInteger(tgt.build) && typeof tgt.version === 'string' && tgt.version.includes(PAPER), JSON.stringify(tgt))
  check('preflight names its version, on-failure WARN and passed server checks', plan?.preflight?.version === version && plan.preflight.onFailure === 'WARN' && plan.preflight.serverChecks === 'passed', JSON.stringify(plan?.preflight))
  check('no stop field when the check could judge', plan && !('stop' in plan))

  writeFileSync(path.join(RUN, 'all-clear.json'), r.out)
  const before2 = tree(SERVER)
  const a = await cli(['apply', '--server', SERVER, '--plan', path.join(RUN, 'all-clear.json'), '--yes'])
  check('apply refuses an all-clear plan (exit 13), with nothing changed', a.code === 13 && /nothing to switch off/i.test(a.err) && sameTree(before2, tree(SERVER)).same, `exit ${a.code}: ${a.err}`)
})

// ---- 4a. Refusals that stop the check before anything is copied or started

async function refusedCheck(label, args, why) {
  const before = tree(SERVER)
  const r = await cli(args)
  const plan = parse(r.out)
  check(`${label}: exit 12, status stop, with the reason`, r.code === 12 && plan?.status === 'stop' && why.test(plan.stop ?? ''), `exit ${r.code}: ${(plan?.stop ?? r.out + r.err).slice(0, 300)}`)
  check(`${label}: no copy made, and the real server untouched`, copies().length === 0 && !/Copying the server/.test(r.err) && sameTree(before, tree(SERVER)).same, copies().join(', ') + sameTree(before, tree(SERVER)).diff)
  return plan
}

await scenario('Check refusals', async () => {
  const p = await refusedCheck('a --paper jar that isn\'t Paper', ['check', '--server', SERVER, '--paper', NOT_PAPER, '--json'], /isn't a Paper server jar/)
  check('a stop plan still has target.file, and the stop field', p?.target?.file === path.basename(NOT_PAPER) && typeof p?.stop === 'string')
  await refusedCheck('a Paper jar for Minecraft 1.21.4', ['check', '--server', SERVER, '--paper', OLD_PAPER, '--json'], /1\.21\.4.*26\.1 or newer/)

  const eula = path.join(SERVER, 'eula.txt'), aside = path.join(RUN, 'eula-aside.txt')
  renameSync(eula, aside)
  try { await refusedCheck('no accepted eula.txt in the server', checkArgs(), /eula=true/) } finally { renameSync(aside, eula) }

  const rulesDir = path.join(SERVER, 'plugins/Preflight/upgrade'), rules = path.join(rulesDir, 'rules.json')
  mkdirSync(rulesDir, { recursive: true })
  try {
    writeFileSync(rules, '{"format": 1, "neverOff": [{"plugin": "PfFixture", "why": "made up"}')
    await refusedCheck('a rules file that isn\'t valid JSON', checkArgs(), /rules\.json couldn't be read/)
    writeFileSync(rules, '{"format": 7, "neverOff": [], "recheck": []}')
    await refusedCheck('a rules file in an unknown format', checkArgs(), /rules format 7/)
    writeFileSync(rules, '{"format": 1, "neverOff": [], "recheck": []}')
    chmodSync(rules, 0o000)
    await refusedCheck('a rules file that can\'t be opened', checkArgs(), /rules\.json/)
  } finally { try { chmodSync(rules, 0o644) } catch {} rmSync(path.join(SERVER, 'plugins/Preflight'), { recursive: true, force: true }) }

  const config = path.join(SERVER, 'plugins/PfFixture/config.yml')
  for (const [db, text] of [['MySQL', 'storage-method: MySQL\n'], ['PostgreSQL', 'storage:\n  type: postgresql\n  host: 127.0.0.1\n'], ['MongoDB', 'database-type: mongodb\n']]) {
    writeFileSync(config, text)
    try { await refusedCheck(`a config pointing at ${db}, without --allow-shared-database`, checkArgs(), /PfFixture uses an outside database.*--allow-shared-database/) } finally { unlinkSync(config) }
  }

  const bad = await cli(['check', '--server', SERVER, '--paper', TARGET, '--no-such-option', 'x'])
  check('an unknown option is bad arguments: exit 2', bad.code === 2 && /doesn't take --no-such-option/.test(bad.err), `exit ${bad.code}: ${bad.err}`)
  const noPlan = await cli(['apply', '--server', SERVER])
  check('apply without --plan is bad arguments: exit 2', noPlan.code === 2, `exit ${noPlan.code}`)
})

await scenario('Shared database allowed', async () => {
  const config = path.join(SERVER, 'plugins/PfFixture/config.yml')
  writeFileSync(config, 'storage-method: MySQL\n')
  try {
    const before = tree(SERVER)
    const r = await cli(checkArgs(['--allow-shared-database']))
    const plan = parse(r.out)
    check('with --allow-shared-database the check runs (exit 0, all-clear)', r.code === 0 && plan?.status === 'all-clear', `exit ${r.code}: ${r.err.slice(-300)}`)
    check('and the plan warns that the copy shared the database', (plan?.warnings ?? []).some(w => /PfFixture uses an outside database.*--allow-shared-database/.test(w)), JSON.stringify(plan?.warnings))
    check('the copy is deleted and the real server untouched', copies().length === 0 && sameTree(before, tree(SERVER)).same)
  } finally { unlinkSync(config) }
})

// ---- 2. Broken plugins, and the whole required-by chain

let switchPlan, pluginsBefore
const PLAN = path.join(RUN, 'switch-off.json')
const pluginsTree = () => Object.fromEntries(Object.entries(tree(path.join(SERVER, 'plugins'))).filter(([k]) => !k.startsWith('Preflight/upgrade/') && k !== 'Preflight/upgrade/' && k !== 'Preflight/'))

await scenario('Broken plugins', async () => {
  setPlugins([...GOOD, ...BROKEN])
  const before = tree(SERVER), w0 = worldFiles()
  const r = await cli(checkArgs())
  const plan = switchPlan = parse(r.out)
  check('check exits 10 when plugins must be switched off', r.code === 10, `exit ${r.code}: ${r.err.slice(-300)}`)
  check('status switch-off', plan?.status === 'switch-off')
  const off = names(plan?.off)
  check('a bad api-version, a missing main class, their hard dependent and its paper-plugin.yml dependent are all offered', JSON.stringify(off) === JSON.stringify(['PfBadApi', 'PfChainEnd', 'PfNeedsBad', 'PfNoMain']), off.join(', '))
  check('the good plugins are not offered', !off.some(n => ['PfFixture', 'PfDependent', 'PreflightExample'].includes(n)))
  const why = n => (plan?.off ?? []).find(o => o.name === n)?.why.join(' ') ?? ''
  check('PfBadApi and PfNoMain: "It didn\'t load."', /didn't load/.test(why('PfBadApi')) && /didn't load/.test(why('PfNoMain')), why('PfBadApi') + ' | ' + why('PfNoMain'))
  check('PfNeedsBad is off because PfBadApi is', /PfBadApi/.test(why('PfNeedsBad')), why('PfNeedsBad'))
  check('PfChainEnd, two steps down the chain, is off because PfNeedsBad is', /PfNeedsBad/.test(why('PfChainEnd')), why('PfChainEnd'))
  const shaOk = (plan?.off ?? []).every(o => o.files.length === 1 && o.files[0] === o.name + '.jar' && o.sha256[o.files[0]] === sha(path.join(SERVER, 'plugins', o.files[0])))
  check('each offered plugin names its jar and the jar\'s real sha256', shaOk, JSON.stringify(plan?.off?.map(o => o.sha256)))
  check('the copy is deleted, the real server and worlds untouched', copies().length === 0 && sameTree(before, tree(SERVER)).same && sameTree(w0, worldFiles()).same)
  writeFileSync(PLAN, r.out)
  const text = await cli(checkArgs().filter(a => a !== '--json'))
  check('the text plan lists them under "Would be switched off" (exit 10)', text.code === 10 && /Would be switched off:/.test(text.out) && /PfChainEnd \(PfChainEnd\.jar\)/.test(text.out), text.out.slice(0, 300))
})

// ---- 4b. apply's refusals, each leaving the real server untouched

async function refusedApply(label, planFile, why, opts = {}) {
  const before = tree(SERVER)
  const r = await cli(['apply', '--server', SERVER, '--plan', planFile, ...(opts.yes === false ? [] : ['--yes'])], opts)
  const t = sameTree(before, tree(SERVER))
  check(`${label}: exit 13 with the reason, nothing changed`, r.code === 13 && why.test(r.err + r.out) && t.same, `changed: [${t.diff}]; exit ${r.code}: ${r.err.slice(0, 250)}`)
}
const editedPlan = (name, edit) => { const p = structuredClone(switchPlan); edit(p); const f = path.join(RUN, name); writeFileSync(f, JSON.stringify(p)); return f }

await scenario('Apply refusals', async () => {
  if (!switchPlan) throw new Error('no switch-off plan from the check above')
  await refusedApply('without --yes and with nothing on stdin', PLAN, /Nothing was changed/, { yes: false })
  await refusedApply('without --yes and without a terminal, even with "yes" piped in', PLAN, /Nothing was changed/, { yes: false, input: 'yes\n' })

  const other = path.join(RUN, 'another-server'); mkdirSync(other)
  await refusedApply('a plan for another server folder', editedPlan('other.json', p => { p.server = other }), /This plan is for .*another-server, not /)
  await refusedApply('a plan for a folder that isn\'t there', editedPlan('gone.json', p => { p.server = path.join(RUN, 'gone') }), /isn't there/)
  await refusedApply('a plan whose status doesn\'t match its contents', editedPlan('edited.json', p => { p.status = 'all-clear' }), /may have been edited/)

  const jar = path.join(SERVER, 'plugins/PfBadApi.jar'), original = readFileSync(jar)
  appendFileSync(jar, Buffer.from([0]))
  try { await refusedApply('a jar changed since the check (sha256)', PLAN, /PfBadApi\.jar has changed since the check/) } finally { writeFileSync(jar, original) }
  renameSync(jar, path.join(RUN, 'PfBadApi.aside'))
  try { await refusedApply('a jar gone since the check', PLAN, /PfBadApi\.jar isn't in the plugins folder/) } finally { renameSync(path.join(RUN, 'PfBadApi.aside'), jar) }

  const disabled = path.join(SERVER, 'plugins/PfNoMain.jar.disabled')
  copyFileSync(path.join(SERVER, 'plugins/PfNoMain.jar'), disabled)
  try { await refusedApply('a .jar.disabled of the same name already there', PLAN, /PfNoMain\.jar\.disabled is already there/) } finally { unlinkSync(disabled) }

  // A world's session.lock held by another process, with nothing on the port.
  const world = readdirSync(SERVER).find(n => existsSync(path.join(SERVER, n, 'level.dat')))
  const lock = path.join(SERVER, world, 'session.lock')
  if (!existsSync(lock)) writeFileSync(lock, '')
  const holder = spawn(JAVA, [HOLD_LOCK, lock], { stdio: ['pipe', 'pipe', 'inherit'] })
  children.add(holder)
  const held = await new Promise(r => { let o = ''; holder.stdout.on('data', d => { o += d; if (o.includes('\n')) r(o.trim()) }); setTimeout(() => r('timeout'), 30000) })
  try {
    check('a helper holds the world\'s session.lock', held === 'held', held)
    await refusedApply('while a world\'s session.lock is held', PLAN, new RegExp(`has ${world} open\\. Stop the server first`))
    const s = await cli(['status', '--server', SERVER, '--json'])
    const st = parse(s.out)
    check('status --json says running, and why', s.code === 0 && st?.running === true && /open/.test(st.runningWhy), s.out)
  } finally { holder.stdin.end(); await new Promise(r => holder.on('exit', r)); children.delete(holder) }
  check('the plugins folder still matches the check', switchPlan.off.every(o => sha(path.join(SERVER, 'plugins', o.files[0])) === o.sha256[o.files[0]]))
})

// ---- 5. apply, status, restore

await scenario('Apply and restore', async () => {
  if (!switchPlan) throw new Error('no switch-off plan from the check above')
  pluginsBefore = pluginsTree()
  const worldsAndConfig = Object.fromEntries(Object.entries(tree(SERVER)).filter(([k]) => !k.startsWith('plugins/')))
  const hashes = Object.fromEntries(readdirSync(path.join(SERVER, 'plugins')).filter(f => f.endsWith('.jar')).map(f => [f, sha(path.join(SERVER, 'plugins', f))]))
  const r = await cli(['apply', '--server', SERVER, '--plan', PLAN, '--yes'])
  check('apply --yes exits 0', r.code === 0, `exit ${r.code}: ${r.err}`)
  check('apply warns that worlds aren\'t in the restore point', /Worlds aren't part of that restore point/.test(r.out))
  const plugins = readdirSync(path.join(SERVER, 'plugins'))
  const offNames = ['PfBadApi', 'PfChainEnd', 'PfNeedsBad', 'PfNoMain']
  check('each offered jar is renamed to .jar.disabled, same bytes', offNames.every(n => !plugins.includes(n + '.jar') && plugins.includes(n + '.jar.disabled') && sha(path.join(SERVER, 'plugins', n + '.jar.disabled')) === hashes[n + '.jar']), plugins.join(', '))
  check('the good jars are where they were', GOOD.every(j => sha(path.join(SERVER, 'plugins', path.basename(j))) === hashes[path.basename(j)]))
  check('nothing outside plugins/ changed', sameTree(worldsAndConfig, Object.fromEntries(Object.entries(tree(SERVER)).filter(([k]) => !k.startsWith('plugins/')))).same)

  const state = path.join(SERVER, 'plugins/Preflight/upgrade')
  const record = parse(existsSync(path.join(state, 'switched-off.json')) ? readFileSync(path.join(state, 'switched-off.json'), 'utf8') : '')
  check('switched-off.json lists the four, with why, date, target and restore point', record?.format === 1 && JSON.stringify(names(record.entries)) === JSON.stringify(offNames)
    && record.entries.every(e => e.why.length > 0 && /^\d{4}-.*Z$/.test(e.date) && e.target.includes(PAPER) && /^restore-points\/\d{8}-\d{6}(-\d+)?\.zip$/.test(e.restorePoint)), JSON.stringify(record))
  const points = existsSync(path.join(state, 'restore-points')) ? readdirSync(path.join(state, 'restore-points')).filter(f => f.endsWith('.zip')) : []
  check('one restore point zip', points.length === 1, points.join(', '))
  const zip = path.join(state, 'restore-points', points[0] ?? 'none')
  if (points.length === 1) {
    const entries = execFileSync('unzip', ['-Z1', zip], { encoding: 'utf8' }).trim().split('\n').sort()
    const expected = [...Object.keys(hashes).map(f => 'plugins/' + f), 'README.txt'].sort()
    check('the zip has every plugin jar as it was, and README.txt', JSON.stringify(entries) === JSON.stringify(expected), entries.join(', '))
    const readme = execFileSync('unzip', ['-p', zip, 'README.txt'], { encoding: 'utf8' })
    const listed = Object.fromEntries(readme.split('\n').map(l => l.match(/^([0-9a-f]{64}) {2}plugins\/(.+)$/)).filter(Boolean).map(m => [m[2], m[1]]))
    check('README.txt gives each jar\'s sha256, matching the originals', Object.keys(hashes).every(f => listed[f] === hashes[f]) && Object.keys(listed).length === Object.keys(hashes).length, JSON.stringify(listed))
    const inZip = Object.keys(hashes).every(f => createHash('sha256').update(execFileSync('unzip', ['-p', zip, 'plugins/' + f], { maxBuffer: 1 << 28 })).digest('hex') === hashes[f])
    check('every jar in the zip is byte-for-byte the original', inZip)
  }

  const s = await cli(['status', '--server', SERVER, '--json'])
  const st = parse(s.out)
  check('status --json: exit 0 and the documented keys', s.code === 0 && st && ['format', 'server', 'running', 'runningWhy', 'switchedOff', 'restorePoints'].every(k => k in st), s.out.slice(0, 300))
  check('status says stopped, lists the four switched off and the one restore point', st?.running === false && /^Stopped/.test(st.runningWhy) && JSON.stringify(names(st.switchedOff)) === JSON.stringify(offNames) && st.restorePoints.length === 1 && st.server === SERVER, s.out.slice(0, 400))
  const sText = await cli(['status', '--server', SERVER])
  check('status in text says the same', sText.code === 0 && /Stopped/.test(sText.out) && /Switched off by this tool:/.test(sText.out) && /PfChainEnd \(PfChainEnd\.jar\)/.test(sText.out), sText.out.slice(0, 300))

  const again = await cli(['apply', '--server', SERVER, '--plan', PLAN, '--yes'])
  check('applying the same plan again is refused (exit 13), nothing changed', again.code === 13 && readdirSync(path.join(state, 'restore-points')).length === 1, `exit ${again.code}: ${again.err}`)

  const unknown = await cli(['restore', '--server', SERVER, '--plugin', 'PfFixture', '--yes'])
  check('restore refuses a plugin this tool didn\'t switch off (exit 13)', unknown.code === 13 && /isn't one this tool switched off/.test(unknown.err), `exit ${unknown.code}: ${unknown.err}`)
  const noYes = await cli(['restore', '--server', SERVER, '--plugin', 'pfchainend'])
  check('restore without --yes and nothing on stdin changes nothing (exit 13)', noYes.code === 13 && existsSync(path.join(SERVER, 'plugins/PfChainEnd.jar.disabled')), `exit ${noYes.code}`)
  const one = await cli(['restore', '--server', SERVER, '--plugin', 'pfchainend', '--yes'])
  check('restore --plugin, matched ignoring case, puts one back (exit 0)', one.code === 0 && existsSync(path.join(SERVER, 'plugins/PfChainEnd.jar')) && !existsSync(path.join(SERVER, 'plugins/PfChainEnd.jar.disabled')), `exit ${one.code}: ${one.out}${one.err}`)

  // A jar with the same name already back (say a newer version): left as it is, nothing overwritten.
  const newer = path.join(SERVER, 'plugins/PfNoMain.jar')
  copyFileSync(GOOD[1], newer)
  const newerSha = sha(newer)
  const clash = await cli(['restore', '--server', SERVER, '--plugin', 'PfNoMain', '--yes'])
  check('restore leaves a plugin off when its jar name is taken, and says so (exit 13)', clash.code === 13 && /already in the plugins folder/.test(clash.out) && sha(newer) === newerSha && existsSync(newer + '.disabled'), `exit ${clash.code}: ${clash.out}`)
  unlinkSync(newer)

  const all = await cli(['restore', '--server', SERVER, '--all', '--yes'])
  check('restore --all puts the rest back (exit 0)', all.code === 0, `exit ${all.code}: ${all.out}${all.err}`)
  const after = sameTree(pluginsBefore, pluginsTree())
  check('every plugin jar and file is back byte-for-byte', after.same, after.diff)
  const s2 = parse((await cli(['status', '--server', SERVER, '--json'])).out)
  check('status then lists nothing switched off, and keeps the restore point', s2?.switchedOff?.length === 0 && s2.restorePoints.length === 1, JSON.stringify(s2))
  const none = await cli(['restore', '--server', SERVER, '--all', '--yes'])
  check('restore --all with nothing off says so (exit 0)', none.code === 0 && /Nothing is switched off/.test(none.out))
})

// ---- 3. A never-off rule on a plugin that has to go

await scenario('Never-off rule', async () => {
  const rulesDir = path.join(SERVER, 'plugins/Preflight/upgrade')
  mkdirSync(rulesDir, { recursive: true })
  writeFileSync(path.join(rulesDir, 'rules.json'), JSON.stringify({
    format: 1,
    neverOff: [{ plugin: 'pfneedsbad', why: 'Made-up rule: the spawn shop needs it.' }],
    recheck: [{ plugin: 'PfFixture', note: 'Open the made-up menu by hand.' }, { plugin: 'NotInstalled', note: 'Never listed.' }],
  }))
  const before = tree(SERVER)
  const r = await cli(checkArgs())
  const plan = parse(r.out)
  check('check exits 11 (wait) when a never-off plugin would have to go', r.code === 11 && plan?.status === 'wait', `exit ${r.code}: ${r.err.slice(-300)}`)
  const b = (plan?.blocked ?? []).find(x => x.name === 'PfNeedsBad')
  check('the protected plugin is under blocked, with the rule and why', b && /spawn shop/.test(b.rule) && /PfBadApi/.test(b.why.join(' ')), JSON.stringify(plan?.blocked))
  check('and not under off', !names(plan?.off).includes('PfNeedsBad'), names(plan?.off).join(', '))
  check('recheck lists installed plugins only', JSON.stringify((plan?.recheck ?? []).map(x => x.plugin)) === '["PfFixture"]', JSON.stringify(plan?.recheck))
  check('the copy is deleted and the real server untouched', copies().length === 0 && sameTree(before, tree(SERVER)).same)
  const f = path.join(RUN, 'wait.json'); writeFileSync(f, r.out)
  await refusedApply('apply on a wait plan', f, /must stay on.*PfNeedsBad.*should wait/)
  rmSync(path.join(rulesDir, 'rules.json'))
})

// ---- 4c. apply while the real server is running

await scenario('Server running', async () => {
  if (!switchPlan) throw new Error('no switch-off plan from the check above')
  const s = new RealServer()
  s.start(MAKER)
  try {
    const done = await s.waitFor(/Done \(/)
    check('the made-up server is running on its port', !!done, s.log.slice(-300))
    // While it runs, its worlds and plugins may change, so compare only the plugins folder minus Paper's own cache.
    const jarsNow = () => Object.fromEntries(readdirSync(path.join(SERVER, 'plugins')).filter(f => /\.jar(\.disabled)?$/.test(f)).map(f => [f, sha(path.join(SERVER, 'plugins', f))]))
    const before = jarsNow()
    const r = await cli(['apply', '--server', SERVER, '--plan', PLAN, '--yes'])
    check('apply refuses while something answers on the port (exit 13)', r.code === 13 && new RegExp(`answering on 127\\.0\\.0\\.1:${PORT}`).test(r.err), `exit ${r.code}: ${r.err}`)
    check('no jar was renamed and no restore point added', sameTree(before, jarsNow()).same && !existsSync(path.join(SERVER, 'plugins/Preflight/upgrade/switched-off.json.partial'))
      && readdirSync(path.join(SERVER, 'plugins/Preflight/upgrade/restore-points')).length === 1)
    const st = parse((await cli(['status', '--server', SERVER, '--json'])).out)
    check('status --json says running, because the port answers', st?.running === true && /answering/.test(st.runningWhy), JSON.stringify(st))
  } finally { await s.stop() }
  check('the made-up server stopped', s.code !== undefined)
})

const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed, made with Paper ${FROM}, checked on Paper ${PAPER}`)
for (const f of failed) console.log('  failed: ' + f.name)
process.exit(failed.length ? 1 : 0)
