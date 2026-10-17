package com.chekayo.feishuantirecall;

public final class RecallBehaviorTest {
    static int checks;
    static String uid = "1001";
    static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    enum Status { NORMAL, RECALL }
    public static final class Message {
        String id = "1", chat = "50", sender = "2002";
        boolean self;
        Object content = new Object();
        Status status = Status.NORMAL;
        public String getId() { return id; }
        public String getChatId() { return chat; }
        public String getFromId() { return sender; }
        public boolean isFromMe() { return self; }
        public boolean isRecalled() { return status == Status.RECALL; }
        public Object getContent() { return content; }
        public Status getStatus() { return status; }
        public void setStatus(Status value) { status = value; }
        public void setMessageContent(Object value) { content = value; }
        void recall() { status = Status.RECALL; content = null; }
    }
    public static class Holder {}
    public static class Model {
        public String getId() { return "1"; }
        public String getChatId() { return "50"; }
    }
    public static class Block {
        private void helper(Holder h, Model m) {}
        protected void changedObfuscatedName(Holder h, Model m) {}
        public void unrelated(Holder h, Object m) {}
    }
    public static class AmbiguousBlock {
        public void a(Holder h, Model m) {}
        public void b(Holder h, Model m) {}
    }
    public interface AuditService {
        void auditImageDownload(String value);
        void auditMediaDownload(String value);
    }
    public static class GoodAccessor { public static AuditService a() { return null; } }
    public static class OldNameReused { public String a(int value) { return ""; } }
    public static class AmbiguousAccessor {
        public static AuditService a() { return null; }
        public static AuditService b() { return null; }
    }
    public static class OutlineShape {
        private android.graphics.Path renamed(float l, float t, float r, float b) { return null; }
        public Object unrelated(float l, float t, float r, float b) { return null; }
    }
    public static class AmbiguousOutline extends OutlineShape {
        private android.graphics.Path a(float l, float t, float r, float b) { return null; }
        private android.graphics.Path b(float l, float t, float r, float b) { return null; }
    }
    public interface ActionContext { Message renamedMessageGetter(); Object unrelated(); }
    enum ActionType { COPY, COPY_URL_LINK, SAVE_TO, MUTE_PLAY_VIDEO, REPLY_IN_THREAD, FORWARD, PIN, ADD_REACTION, UNKNOWN }
    public static class Action {
        final ActionType type;
        Action(ActionType type) { this.type = type; }
        public ActionType getType() { return type; }
    }
    public static class TranslationInfo {
        final Message message;
        TranslationInfo(String id) { message = new Message(); message.id = id; }
        public Message getMessage() { return message; }
    }
    public static class TranslationModel {
        public TranslationInfo getMessageInfoById(String id) { return new TranslationInfo(id); }
    }
    public static class TranslationHandler { public TranslationModel model = new TranslationModel(); }
    public static void main(String[] args) throws Throwable {
        java.io.File markerDirectory = new java.io.File("build/recall-mark-test-" + System.nanoTime());
        RecallMessages.markerStore = new RecallMarkStore(markerDirectory);
        final int[] accountReads = {0};
        RecallMessages.accountReader = new RecallMessages.AccountReader() {
            public String read() {
                accountReads[0]++;
                return uid;
            }
        };
        RecallMessages.applicationReady = false;
        check(RecallMessages.account().isEmpty() && accountReads[0] == 0, "startup does not initialize Passport before Application");
        RecallMessages.applicationReady = true;
        Config.antirecall = true;
        AntiRecall.TAMPER = 1;
        Message other = new Message();
        Object original = other.content;
        RecallMessages.prepare(other);
        other.recall();
        RecallMessages.prepare(other);
        check(other.content == original && other.status == Status.NORMAL, "restore exact other message");
        check(RecallMessages.marked("1001", "50", "1"), "other marked");
        Message self = new Message(); self.id = "2"; self.sender = "1001"; self.self = true;
        RecallMessages.prepare(self); self.recall(); RecallMessages.prepare(self);
        check(self.content == null && self.status == Status.RECALL, "self never restored");
        check(!RecallMessages.marked("1001", "50", "2"), "self not marked");
        Message contradictory = new Message(); contradictory.id = "3"; contradictory.sender = "1001";
        RecallMessages.prepare(contradictory); contradictory.recall(); RecallMessages.prepare(contradictory);
        check(contradictory.content == null, "sender match also excludes self");
        Message noOriginal = new Message(); noOriginal.id = "4"; noOriginal.recall();
        RecallMessages.prepare(noOriginal);
        check(noOriginal.content == null, "missing original does not use another message");
        Message anotherChat = new Message(); anotherChat.chat = "51"; anotherChat.recall();
        RecallMessages.prepare(anotherChat);
        check(anotherChat.content == null, "chat isolation");
        uid = "3003";
        Message switched = new Message(); switched.recall(); RecallMessages.prepare(switched);
        check(switched.content == null, "account switch isolates content");
        uid = "";
        Message unknown = new Message(); RecallMessages.prepare(unknown); unknown.recall(); RecallMessages.prepare(unknown);
        check(unknown.content == null, "unknown login passes through");
        uid = "1001";
        Config.antirecall = false;
        Message disabled = new Message(); disabled.recall(); RecallMessages.prepare(disabled);
        check(disabled.content == null, "disabled passes through even with cache");
        Config.antirecall = true;
        AntiRecall.TAMPER = 2;
        RecallMessages.prepare(disabled);
        check(disabled.content == null, "signature gate respected");
        AntiRecall.TAMPER = 1;
        Message ordinaryEmpty = new Message();
        ordinaryEmpty.content = null;
        RecallMessages.prepare(ordinaryEmpty);
        check(ordinaryEmpty.content == null, "ordinary empty message is not a recall");
        check(RecallMessages.positive("9223372036854775808") == 0, "overflow rejected");
        check(RecallMessages.key("1001", "", "1") == null, "incomplete identity rejected");
        check(RecallBubbleUi.findBind(Block.class, Holder.class).getName().equals("changedObfuscatedName"), "bind discovered by shape, not short name");
        boolean rejected = false;
        try { RecallBubbleUi.findBind(AmbiguousBlock.class, Holder.class); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "ambiguous bind rejected");
        check(FileDownloadUnlock.auditAccessor(GoodAccessor.class) != null, "verified audit interface accepted");
        check(FileDownloadUnlock.auditAccessor(OldNameReused.class) == null, "reused short class name rejected");
        check(FileDownloadUnlock.auditAccessor(AmbiguousAccessor.class) == null, "ambiguous audit accessor rejected");
        check(AntiRecall.isOwnRecallNotice("你撤回了一条消息"), "own notice left native");
        check(AntiRecall.isOwnRecallNotice("You recalled a message"), "English own notice");
        check(!AntiRecall.isOwnRecallNotice("测试用户撤回了一条消息"), "other notice");
        RecallMessages.clear();
        RecallMessages.markerStore.closeForTest();
        RecallMessages.markerStore = new RecallMarkStore(markerDirectory);
        RecallMessages.markerStore.awaitIdle();
        check(RecallMessages.marked("1001", "50", "1"), "marker survives process/cache restart before native snapshot");
        check(!RecallMessages.markerStore.contains("3003", "50", "1"), "persisted markers isolated by account");
        check(!RecallMessages.markerStore.contains("1001", "51", "1"), "persisted markers isolated by chat");
        RecallMessages.markerStore.add("1001", "50", "8");
        RecallMessages.markerStore.closeForTest();
        RecallMessages.markerStore = new RecallMarkStore(markerDirectory);
        RecallMessages.markerStore.awaitIdle();
        check(RecallMessages.markerStore.contains("1001", "50", "1")
                && RecallMessages.markerStore.contains("1001", "50", "8"), "atomic replacement preserves earlier marks");
        RecallMessages.markerStore.add("../outside", "50", "9");
        check(!java.util.Arrays.asList(RecallMessages.markerStore.knownAccounts()).contains("../outside"), "invalid account paths rejected");
        check(RecallBubbleUi.findOutline(OutlineShape.class).getName().equals("renamed"), "host outline resolved by exact path signature");
        rejected = false;
        try { RecallBubbleUi.findOutline(AmbiguousOutline.class); }
        catch (NoSuchMethodException expected) { rejected = true; }
        check(rejected, "ambiguous outline rejected instead of drawing a guessed shape");
        check(RecallActionGuard.findMessageGetter(ActionContext.class, Message.class).getName().equals("renamedMessageGetter"), "menu context message resolved by type");
        Config.showRecallHint = false;
        check(RecallActionGuard.blockedMessage(other), "retained target stays read-only when border hidden");
        check(RecallActionGuard.blockedId("1", null), "reaction id found after restart without read-receipt mapping");
        check(!RecallActionGuard.blockedId("11", null), "message id lookup uses exact suffix boundary");
        uid = "3003";
        check(!RecallActionGuard.blockedMessage(other) && !RecallActionGuard.blockedId("1", null), "action guard isolates switched accounts");
        uid = "1001";
        final Message target = new Message(); target.id = "10";
        RecallActionGuard.Target resolver = new RecallActionGuard.Target() {
            public boolean blocked(Object receiver, java.util.List<Object> args) { return RecallActionGuard.blockedMessage(target); }
        };
        RecallActionGuard.Guard clickGuard = new RecallActionGuard.Guard(resolver, false);
        HookMigrationBehaviorTest.FakeChain allowed = new HookMigrationBehaviorTest.FakeChain(null, null, new Object[0], "native-result");
        check("native-result".equals(clickGuard.intercept(allowed)) && allowed.proceedCount == 1, "ordinary message action executes exactly once");
        RecallMessages.prepare(target); target.recall(); RecallMessages.prepare(target);
        HookMigrationBehaviorTest.FakeChain stale = new HookMigrationBehaviorTest.FakeChain(null, null, new Object[0], null);
        check(clickGuard.intercept(stale) == null && stale.proceedCount == 0, "already-open action blocked when target recalled before click");
        HookMigrationBehaviorTest.FakeChain visibility = new HookMigrationBehaviorTest.FakeChain(null, null, new Object[0], true);
        check(Boolean.FALSE.equals(new RecallActionGuard.Guard(resolver, true).intercept(visibility))
                && visibility.proceedCount == 0, "recalled reply/reaction entries hidden without invoking host");
        Config.antiread = true;
        AntiRecall.KN_READY = false;
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.MSG2CH.put("1", "50");
        java.util.LinkedHashSet<String> pending = new java.util.LinkedHashSet<String>(); pending.add("1");
        AntiRecall.PENDING_READ.put("50", pending);
        HookMigrationBehaviorTest.FakeChain request = new HookMigrationBehaviorTest.FakeChain(null, null, new Object[]{"1", "OK"}, null);
        new AntiRecall.ReactionHook().intercept(request);
        check(!AntiRecall.READ_WINDOWS.containsKey("50") && AntiRecall.PENDING_READ.get("50") == pending,
                "recalled reaction constructor cannot open read window or consume pending receipts");
        check(request.proceedCount == 1, "defensive read guard keeps request construction valid");
        AntiRecall.MSG2CH.put("20", "50");
        new AntiRecall.ReactionHook().intercept(new HookMigrationBehaviorTest.FakeChain(null, null, new Object[]{"20", "OK"}, null));
        check(AntiRecall.READ_WINDOWS.containsKey("50"), "ordinary reaction preserves read receipt behavior");
        for (ActionType type : new ActionType[]{ActionType.COPY, ActionType.COPY_URL_LINK, ActionType.SAVE_TO, ActionType.MUTE_PLAY_VIDEO}) {
            check(!RecallActionGuard.blockedAction(new Action(type), other), "retained message permits content action " + type);
        }
        Message ordinary = new Message(); ordinary.id = "99";
        for (ActionType type : new ActionType[]{ActionType.REPLY_IN_THREAD, ActionType.FORWARD, ActionType.PIN, ActionType.ADD_REACTION, ActionType.UNKNOWN}) {
            final Action action = new Action(type);
            check(RecallActionGuard.blockedAction(action, other), "retained message denies interaction " + type);
            check(!RecallActionGuard.blockedAction(action, ordinary), "ordinary message preserves action " + type);
            HookMigrationBehaviorTest.FakeChain staleMenu = new HookMigrationBehaviorTest.FakeChain(null, action, new Object[0], null);
            new RecallActionGuard.Guard(new RecallActionGuard.Target() {
                public boolean blocked(Object receiver, java.util.List<Object> args) { return RecallActionGuard.blockedAction(receiver, other); }
            }, false).intercept(staleMenu);
            check(staleMenu.proceedCount == 0, "stale menu cannot execute " + type);
        }
        check(RecallActionGuard.blockedMessage(new Model()), "swipe model identity detects persisted recall without status field");
        uid = "3003";
        check(!RecallActionGuard.blockedAction(new Action(ActionType.FORWARD), other)
                && !RecallActionGuard.blockedMessage(new Model()), "menu and swipe policies isolated by active account");
        uid = "1001";
        TranslationHandler dialog = new TranslationHandler();
        RecallTranslationUi.handlerAccounts.put(dialog, uid);
        RecallTranslationUi.DialogTarget dialogTarget = new RecallTranslationUi.DialogTarget(
                TranslationHandler.class.getField("model"),
                TranslationModel.class.getMethod("getMessageInfoById", String.class),
                TranslationInfo.class.getMethod("getMessage"));
        RecallActionGuard.Guard submission = new RecallActionGuard.Guard(dialogTarget, false);
        HookMigrationBehaviorTest.FakeChain ordinaryDialog = new HookMigrationBehaviorTest.FakeChain(
                null, dialog, new Object[]{null, "en", "30"}, null);
        submission.intercept(ordinaryDialog);
        check(ordinaryDialog.proceedCount == 1, "ordinary translation dialog submits exactly once");
        RecallMessages.markerStore.add("1001", "50", "30");
        HookMigrationBehaviorTest.FakeChain staleDialog = new HookMigrationBehaviorTest.FakeChain(
                null, dialog, new Object[]{new Model(), "en", "30"}, null);
        submission.intercept(staleDialog);
        check(staleDialog.proceedCount == 0, "open translation picker blocked by captured id even after anchor recycled");
        uid = "3003";
        check(dialogTarget.blocked(dialog, java.util.Arrays.<Object>asList(null, "en", "30")),
                "old picker keeps its owning account across a login switch");
        TranslationHandler newDialog = new TranslationHandler();
        RecallTranslationUi.handlerAccounts.put(newDialog, uid);
        check(!dialogTarget.blocked(newDialog, java.util.Arrays.<Object>asList(null, "en", "30")),
                "new account picker does not inherit old account recall marker");
        uid = "1001";
        RecallMessages.markerStore.closeForTest();
        System.out.println("PASS: " + checks + " Java recall checks");
    }
}
