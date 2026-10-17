#include "../../native/jni/recall_filter.h"
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

static int (*open_db)(const char*, void**);
static int (*close_db)(void*);
static char *(*expanded)(void*);
static void (*free_sql)(void*);
static int (*bind_int)(void*, int, long long);
static int (*bind_text)(void*, int, const char*, int, void*);
static int (*bind_blob)(void*, int, const void*, int, void*);
static int (*reset_stmt)(void*);
static const char* (*raw_filename)(void*, const char*);
static const char* normalized_filename(void* db, const char* schema) {
    const char* value = raw_filename(db, schema);
    if (!value) return nullptr;
    thread_local std::string path;
    path = value;
    std::replace(path.begin(), path.end(), '\\', '/'); // host-only path separator normalization
    return path.c_str();
}
static recall::Api api;
static int checks;
static void check(bool ok, const char *label) {
    ++checks;
    if (std::getenv("RECALL_TEST_VERBOSE")) std::fprintf(stderr, "%d: %s\n", checks, label);
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", label); std::exit(1); }
}
static long long scalar(void *db, const std::string &sql) {
    recall::Statement q(api, db, sql);
    check(q.step() == recall::ROW, "scalar query");
    return api.integer(q.s, 0);
}
static void run(void *db, const std::string &sql) {
    check(recall::exec(api, db, sql) == recall::DONE, sql.c_str());
}
static int interceptStatement(recall::Store &store, void *db, void *stmt) {
    store.observe(api, &api, db, true);
    char *e = expanded(stmt);
    check(e != nullptr, "expanded SQL available");
    int result = store.filter(api, &api, db, e, stmt);
    if (e) free_sql(e);
    if (result == recall::PASS) result = api.step(stmt);
    store.observe(api, &api, db, true);
    return result;
}
static int intercepted(recall::Store &store, void *db, const std::string &sql) {
    recall::Statement q(api, db, sql);
    check(q.s != nullptr, "host statement prepares");
    return interceptStatement(store, db, q.s);
}

static void concurrency(void *db, const std::filesystem::path &file) {
    check(scalar(db, "PRAGMA journal_mode=WAL") == 0, "WAL enabled"); // text column converts to zero
    void *locker = nullptr;
    check(open_db(file.generic_string().c_str(), &locker) == 0, "second connection");
    recall::Store store;
    for (bool replace : {false, true}) for (bool self : {false, true}) {
        run(db, "INSERT OR REPLACE INTO messages VALUES(80,50," + std::string(self ? "1001" : "2002") + ",x'112233',0,'lock',80)");
        std::string sql = replace
            ? "REPLACE INTO messages(id,chat_id,from_id,content,is_recalled,cid,position) VALUES(80,50,2002,x'',1,'lock',80)"
            : "UPDATE messages SET content=?,is_recalled=1 WHERE id=?";
        recall::Statement q(api, db, sql);
        if (!replace) { bind_blob(q.s,1,"",0,nullptr); bind_int(q.s,2,80); }
        run(locker, "BEGIN IMMEDIATE");
        check(interceptStatement(store,db,q.s) == 5, "write lock returns SQLITE_BUSY");
        if (!self || !replace) check(!api.busy(q.s), "protected retry has no active original statement");
        else check(api.busy(q.s), "own REPLACE preserves native busy statement");
        run(locker,"COMMIT");
        check(interceptStatement(store,db,q.s) == recall::DONE, "same statement retries without caller reset");
        check(scalar(db,"SELECT length(content) FROM messages WHERE id=80") == (self ? 0 : 3), "retry preserves other/removes own");
        check(api.autocommit(db) == 1, "retry leaves no transaction");
        check(scalar(db,"SELECT count(*) FROM sqlite_temp_master WHERE name='feishukit_recall_update_guard'") == 0, "temporary guard removed");
    }
    run(db,"INSERT INTO messages VALUES(81,50,2002,x'1234',0,'wal',81)");
    store.observe(api,&api,locker,true);
    run(locker,"BEGIN");
    recall::Statement cursor(api,locker,"SELECT id FROM messages");
    check(cursor.step() == recall::ROW, "old read cursor active");
    // A prior write in this transaction marks this connection as pending.
    store.changed(api,&api,locker,normalized_filename(locker,"main"));
    check(intercepted(store,db,"UPDATE messages SET content=x'',is_recalled=1 WHERE id=81") == recall::DONE, "concurrent writer commits marker");
    check(store.contains("1001",50,81), "committed marker visible");
    run(locker,"ROLLBACK");
    check(api.autocommit(locker) && api.busy(cursor.s), "autocommit with old cursor is not a fresh snapshot");
    store.observe(api,&api,locker,true);
    check(store.contains("1001",50,81), "stale observation cannot retract committed marker");
    recall::Store restarted;
    restarted.observe(api,&api,locker,true);
    check(!restarted.contains("1001",50,81), "first stale observation deferred");
    reset_stmt(cursor.s);
    store.observe(api,&api,locker,false);
    restarted.observe(api,&api,locker,false);
    check(store.contains("1001",50,81) && restarted.contains("1001",50,81), "cursor end refreshes pending snapshots");
    api.finalize(cursor.s); cursor.s = nullptr;
    check(close_db(locker) == 0,"second connection closed");
}

static void bindings(void *db) {
    recall::Store store;
    const char bytes[] = {'A',0,'B'};
    // SET and WHERE slots, repeated names and numbered gaps are left to SQLite.
    for (const char *sql : {
            "UPDATE messages SET content=?,is_recalled=1 WHERE id IN (?,?)",
            "UPDATE messages SET content=:value,is_recalled=1 WHERE id=:own OR id=:other OR id=:own",
            "UPDATE messages SET content=?3,is_recalled=1 WHERE id=?1 OR id=?5"}) {
        bool numbered = std::string(sql).find("?3") != std::string::npos;
        for (bool blob : {false,true}) {
            run(db,"INSERT OR REPLACE INTO messages VALUES(90,50,1001,x'1122',0,'nul-own',90),(91,50,2002,x'3344',0,'nul-other',91)");
            recall::Statement q(api,db,sql);
            int valueSlot = numbered ? 3 : 1, ownSlot = numbered ? 1 : 2, otherSlot = numbered ? 5 : 3;
            check((blob ? bind_blob(q.s,valueSlot,bytes,3,nullptr) : bind_text(q.s,valueSlot,bytes,3,nullptr)) == 0,"bind embedded NUL");
            bind_int(q.s,ownSlot,90); bind_int(q.s,otherSlot,91);
            for (int repeat = 0; repeat < 2; ++repeat) {
                check(interceptStatement(store,db,q.s) == recall::DONE,"mixed parameterized original executes");
                check(scalar(db,"SELECT hex(content)='410042' AND length(CAST(content AS BLOB))=3 FROM messages WHERE id=90") == 1,"all NUL bytes preserved");
                check(scalar(db,std::string("SELECT typeof(content)='") + (blob ? "blob" : "text") + "' FROM messages WHERE id=90") == 1,"binding type preserved");
                check(scalar(db,"SELECT hex(content)='3344' AND is_recalled=0 FROM messages WHERE id=91") == 1,"other original retained");
                reset_stmt(q.s); // must not consume or corrupt bindings on reuse
            }
        }
    }
    // A NUL in a WHERE parameter must not match the truncated prefix.
    run(db,"INSERT INTO messages VALUES(92,50,2002,x'5566',0,'A',92)");
    recall::Statement q(api,db,"UPDATE messages SET content=x'',is_recalled=1 WHERE cid=?");
    bind_text(q.s,1,bytes,3,nullptr);
    check(interceptStatement(store,db,q.s) == recall::DONE,"NUL WHERE executes original predicate");
    check(!store.contains("1001",50,92),"expanded WHERE cannot invent a recall target");
}

int main(int argc, char **argv) {
    if (argc != 2) { std::fprintf(stderr,"usage: native_test <sqlite library>\n"); return 2; }
#ifdef _WIN32
    HMODULE lib = LoadLibraryA(argv[1]);
    auto symbol = [lib](const char *s) { return (void*)GetProcAddress(lib, s); };
#else
    void *lib = dlopen(argv[1], RTLD_NOW);
    auto symbol = [lib](const char *s) { return dlsym(lib, s); };
#endif
    check(lib != nullptr, "load SQLite");
    auto initialize = reinterpret_cast<int(*)()>(symbol("sqlite3_initialize"));
    check(initialize && initialize() == 0, "initialize SQLite");
#define LOAD(target, name) target = reinterpret_cast<decltype(target)>(symbol(name)); check(target != nullptr, name)
    LOAD(open_db, "sqlite3_open"); LOAD(close_db, "sqlite3_close");
    LOAD(expanded, "sqlite3_expanded_sql"); LOAD(free_sql, "sqlite3_free");
    LOAD(bind_int, "sqlite3_bind_int64"); LOAD(reset_stmt, "sqlite3_reset");
    LOAD(bind_text, "sqlite3_bind_text"); LOAD(bind_blob, "sqlite3_bind_blob");
    LOAD(api.step, "sqlite3_step"); LOAD(api.prepare, "sqlite3_prepare_v2");
    LOAD(api.finalize, "sqlite3_finalize"); LOAD(api.integer, "sqlite3_column_int64");
    LOAD(raw_filename, "sqlite3_db_filename"); api.filename = normalized_filename;
    LOAD(api.autocommit, "sqlite3_get_autocommit");
    LOAD(api.next_stmt, "sqlite3_next_stmt"); LOAD(api.busy, "sqlite3_stmt_busy");
    LOAD(api.reset, "sqlite3_reset"); LOAD(api.error, "sqlite3_errmsg");
    LOAD(api.db_mutex, "sqlite3_db_mutex"); LOAD(api.enter, "sqlite3_mutex_enter"); LOAD(api.leave, "sqlite3_mutex_leave");

    auto root = std::filesystem::current_path() / "sdk_storage" / "1001";
    std::filesystem::create_directories(root);
    void *db = nullptr;
    check(open_db((root / "messages.db").generic_string().c_str(), &db) == 0, "open messages DB");
    run(db, "CREATE TABLE messages(id INTEGER PRIMARY KEY,chat_id INTEGER,from_id INTEGER,content BLOB,is_recalled INTEGER,cid TEXT UNIQUE,position INTEGER)");
    run(db, "INSERT INTO messages VALUES(1,50,1001,x'112233',0,'self',1),(2,50,2002,x'445566',0,'other',2),(3,50,1001,x'112233',0,'self3',3),(4,50,2002,x'445566',0,'other4',4),(5,50,NULL,x'112233',0,'unknown',5)");
    recall::Store store;
    store.observe(api, &api, db, true);
    check(intercepted(store, db, "REPLACE INTO messages(id,chat_id,from_id,content,is_recalled,cid,position) VALUES(1,50,1001,x'',1,'self',1)") == recall::DONE, "own REPLACE succeeds");
    check(scalar(db,"SELECT is_recalled=1 AND length(content)=0 FROM messages WHERE id=1") == 1, "own recall removes original");
    check(!store.contains("1001",50,1), "no self marker");
    check(intercepted(store, db, "INSERT OR REPLACE INTO `messages` (`content`,`is_recalled`,`id`,`from_id`,`chat_id`,`cid`,`position`) VALUES(x'',1,2,2002,50,'other',2)") == recall::DONE, "other reordered columns");
    check(scalar(db,"SELECT is_recalled=0 AND length(content)=3 AND position=2 AND cid='other' FROM messages WHERE id=2") == 1, "other original/position/cid preserved");
    check(scalar(db,"SELECT count(*) FROM messages") == 5, "no additional message row");
    check(store.contains("1001",50,2), "committed marker published");
    check(!store.contains("2002",50,2) && !store.contains("1001",51,2), "account/chat isolation");
    check(intercepted(store, db, "update \"messages\" set content=x'', is_recalled=1 where id=3 OR id=4 OR id=5;") == recall::DONE, "mixed UPDATE succeeds");
    check(scalar(db,"SELECT sum(is_recalled) FROM messages WHERE id IN(3,4,5)") == 2, "mixed update applies own/unknown only");
    check(store.contains("1001",50,4) && !store.contains("1001",50,3), "mixed marker belongs to other");
    check(intercepted(store, db, "UPDATE messages SET content='is_recalled=1 WHERE fake' WHERE id=2") == recall::DONE, "quoted recall text is ordinary content");
    check(!recall::parse("UPDATE messages SET is_recalled=10 WHERE id=2").valid, "strict recalled value");
    check(!recall::parse("UPDATE messages SET content='is_recalled=1' WHERE id=2").valid, "assignment parser ignores content");
    check(!recall::parse("REPLACE INTO messages VALUES(2,50,2002,x'',1,'other',2)").valid, "unknown column order passes through");
    check(!recall::parse("UPDATE messages SET is_recalled=1 WHERE id=2 RETURNING id").valid, "RETURNING is not rewritten");
    check(!recall::parse("REPLACE INTO messages(id,is_recalled) VALUES(2,1),(3,1)").valid, "multi-row replace is not partially blocked");
    run(db,"INSERT INTO messages VALUES(6,50,2002,x'1122',0,'other6',6),(7,50,2002,x'1122',0,'other7',7)");
    run(db,"BEGIN");
    check(intercepted(store,db,"UPDATE messages SET is_recalled=1,content=x'' WHERE id=6") == recall::DONE,"transaction recall");
    check(!store.contains("1001",50,6),"uncommitted marker hidden");
    check(intercepted(store,db,"ROLLBACK") == recall::DONE,"rollback");
    check(!store.contains("1001",50,6),"rollback removes marker");
    run(db,"BEGIN");
    intercepted(store,db,"UPDATE messages SET is_recalled=1,content=x'' WHERE id=6");
    intercepted(store,db,"COMMIT");
    check(store.contains("1001",50,6),"commit publishes marker");
    // First access while a transaction is open must not publish its changes either.
    recall::Store firstInTransaction;
    run(db,"BEGIN");
    intercepted(firstInTransaction,db,"UPDATE messages SET is_recalled=1,content=x'' WHERE id=7");
    check(!firstInTransaction.contains("1001",50,7),"first transaction does not leak snapshot");
    intercepted(firstInTransaction,db,"ROLLBACK");
    check(!firstInTransaction.contains("1001",50,7),"first rollback remains unmarked");
    recall::Store restarted;
    restarted.observe(api,&api,db,true);
    check(restarted.contains("1001",50,2) && restarted.contains("1001",50,6),"markers survive process restart");
    // Reused prepared statements, with different bindings, retain correct ownership.
    {
        recall::Statement q(api,db,"UPDATE messages SET is_recalled=1,content=x'' WHERE id=?");
        for (long long id : {2LL,3LL,2LL}) {
            bind_int(q.s,1,id);
            char *e=expanded(q.s);
            int rc=store.filter(api,&api,db,e,q.s);
            free_sql(e);
            if(rc==recall::PASS)rc=q.step();
            check(rc==recall::DONE,"reused statement");
            reset_stmt(q.s);
        }
    }
    bindings(db);
    concurrency(db,root / "messages.db");
    run(db,"DROP TABLE feishukit_recalled_v1");
    run(db,"CREATE TABLE feishukit_recalled_v1(wrong_column INTEGER)");
    check(intercepted(store,db,"UPDATE messages SET is_recalled=1,content=x'' WHERE id=7") != recall::DONE,"metadata failure reports error");
    check(scalar(db,"SELECT is_recalled=0 AND length(content)>0 FROM messages WHERE id=7")==1,"metadata error cannot destroy original");
    check(api.autocommit(db)==1,"failure leaves no savepoint open");
    check(close_db(db)==0,"all statements finalized");
    check(recall::owner("/sdk_storage/unknown/messages.db").empty(),"unknown owner is not guessed");
    recall::bindOwner("0123456789abcdef0123456789abcdef","1001");
    check(recall::owner("/sdk_storage/0123456789abcdef0123456789abcdef/messages.db")=="1001","hashed SDK directory mapping");
    check(recall::number("9223372036854775808")==0,"ID overflow rejected");
    std::printf("PASS: %d native recall checks\n",checks);
}
