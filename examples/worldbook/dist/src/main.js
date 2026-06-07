"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.systemPromptHook = systemPromptHook;
exports.finalizeHook = finalizeHook;
exports.registerToolPkg = registerToolPkg;
const index_ui_js_1 = __importDefault(require("./ui/worldbook_manager/index.ui.js"));
const worldbook_storage_js_1 = require("./shared/worldbook_storage.js");
const WORLDBOOK_ROUTE = "toolpkg:com.operit.worldbook:ui:worldbook_manager";
function matchesEntry(entry, text) {
    if (!entry.keywords || entry.keywords.length === 0) {
        return false;
    }
    for (const keyword of entry.keywords) {
        if (!keyword) {
            continue;
        }
        try {
            if (entry.is_regex) {
                if (new RegExp(keyword, entry.case_sensitive ? "g" : "gi").test(text)) {
                    return true;
                }
                continue;
            }
            if (entry.case_sensitive) {
                if (text.includes(keyword)) {
                    return true;
                }
            }
            else if (text.toLowerCase().includes(keyword.toLowerCase())) {
                return true;
            }
        }
        catch (_error) {
            // Ignore malformed regex entries instead of breaking prompt assembly.
        }
    }
    return false;
}
function buildInjection(entries) {
    const parts = ["<worldbook>"];
    for (const entry of entries) {
        parts.push(`<entry name="${entry.name}">`);
        parts.push(entry.content);
        parts.push("</entry>");
    }
    parts.push("</worldbook>");
    return parts.join("\n");
}
function matchesCharacterCard(entry, callerCardId) {
    const targetCardId = (entry.character_card_id || "").trim();
    if (!targetCardId) {
        return true;
    }
    return !!callerCardId && callerCardId === targetCardId;
}
async function resolveCurrentCharacterCardId(event) {
    try {
        const directCardId = typeof getCallerCardId === "function" ? getCallerCardId() : undefined;
        if (directCardId && String(directCardId).trim()) {
            return String(directCardId).trim();
        }
    }
    catch (_error) {
        // Ignore direct getter failure and fall back to chat lookup.
    }
    try {
        const chatId = event?.eventPayload?.chatId;
        if (!chatId) {
            return "";
        }
        const chatResult = await Tools.Chat.findChat({
            query: String(chatId),
            match: "exact",
            index: 0
        });
        const cardName = String(chatResult?.chat?.characterCardName || "").trim();
        if (!cardName) {
            return "";
        }
        const cardResult = await Tools.Chat.listCharacterCards();
        const cards = Array.isArray(cardResult?.cards)
            ? cardResult.cards
            : [];
        const matchedCard = cards.find((card) => String(card?.name || "").trim() === cardName);
        return matchedCard?.id ? String(matchedCard.id).trim() : "";
    }
    catch (_error) {
        return "";
    }
}
async function readEnabledEntries() {
    try {
        const parsed = await (0, worldbook_storage_js_1.readWorldBookEntries)();
        const enabledEntries = parsed.filter((entry) => entry && entry.enabled !== false);
        enabledEntries.sort((left, right) => (right.priority || 50) - (left.priority || 50));
        return enabledEntries;
    }
    catch (_error) {
        return [];
    }
}
async function systemPromptHook(event) {
    const stage = event.eventName || event.event;
    if (stage !== "after_compose_system_prompt") {
        return null;
    }
    const enabledEntries = await readEnabledEntries();
    const callerCardId = await resolveCurrentCharacterCardId(event);
    const hitEntries = enabledEntries.filter((entry) => entry.always_active &&
        entry.inject_target !== "user" &&
        matchesCharacterCard(entry, callerCardId));
    if (hitEntries.length === 0) {
        return null;
    }
    const currentPrompt = event.eventPayload?.systemPrompt || "";
    return { systemPrompt: `${currentPrompt}\n${buildInjection(hitEntries)}` };
}
async function finalizeHook(event) {
    const stage = event.eventName || event.event;
    if (stage !== "before_finalize_prompt") {
        return null;
    }
    const enabledEntries = await readEnabledEntries();
    const promptEntries = enabledEntries.filter((entry) => entry.inject_target === "user");
    const keywordEntries = enabledEntries.filter((entry) => !entry.always_active);
    const payload = event.eventPayload || {};
    const history = (payload.preparedHistory || payload.chatHistory || []);
    const callerCardId = await resolveCurrentCharacterCardId(event);
    const hitSystemEntries = [];
    const hitUserEntries = [];
    for (const entry of promptEntries) {
        if (!matchesCharacterCard(entry, callerCardId)) {
            continue;
        }
        if (entry.always_active) {
            hitUserEntries.push(entry);
        }
    }
    for (const entry of keywordEntries) {
        if (!matchesCharacterCard(entry, callerCardId)) {
            continue;
        }
        const texts = [];
        if (payload.rawInput) {
            texts.push(payload.rawInput);
        }
        if (payload.processedInput && payload.processedInput !== payload.rawInput) {
            texts.push(payload.processedInput);
        }
        const depth = entry.scan_depth != null ? entry.scan_depth : 0;
        if (depth > 0) {
            const userTurns = history.filter((turn) => turn && turn.kind === "USER" && turn.content);
            const startIndex = Math.max(0, userTurns.length - depth);
            for (let index = startIndex; index < userTurns.length; index += 1) {
                const turn = userTurns[index];
                if (turn?.content) {
                    texts.push(turn.content);
                }
            }
        }
        const scanText = texts.join("\n");
        if (scanText && matchesEntry(entry, scanText)) {
            if (entry.inject_target === "user") {
                hitUserEntries.push(entry);
            }
            else {
                hitSystemEntries.push(entry);
            }
        }
    }
    if (hitSystemEntries.length === 0 && hitUserEntries.length === 0) {
        return null;
    }
    let nextHistory = [...history];
    let nextProcessedInput = String(payload.processedInput || payload.rawInput || "");
    if (hitSystemEntries.length > 0) {
        const sysInjection = buildInjection(hitSystemEntries);
        let injected = false;
        const sysNext = [];
        for (const turn of nextHistory) {
            if (!injected && turn.kind === "SYSTEM") {
                const nextContent = turn.content ? `${turn.content}\n${sysInjection}` : sysInjection;
                sysNext.push({ ...turn, content: nextContent });
                injected = true;
                continue;
            }
            sysNext.push(turn);
        }
        if (!injected) {
            sysNext.unshift({ kind: "SYSTEM", content: sysInjection });
        }
        nextHistory = sysNext;
    }
    if (hitUserEntries.length > 0) {
        const userInjection = `${buildInjection(hitUserEntries)}\n`;
        nextProcessedInput = userInjection + nextProcessedInput;
    }
    return {
        preparedHistory: nextHistory,
        processedInput: nextProcessedInput
    };
}
function registerToolPkg() {
    void (0, worldbook_storage_js_1.ensureWorldBookStorage)();
    ToolPkg.registerUiRoute({
        id: "worldbook_manager",
        route: WORLDBOOK_ROUTE,
        runtime: "compose_dsl",
        screen: index_ui_js_1.default,
        params: {},
        title: {
            zh: "世界书管理",
            en: "World Book Manager"
        }
    });
    ToolPkg.registerNavigationEntry({
        id: "worldbook_manager_toolbox",
        route: WORLDBOOK_ROUTE,
        surface: "toolbox",
        title: {
            zh: "世界书管理",
            en: "World Book Manager"
        },
        icon: "Book",
        order: 210
    });
    ToolPkg.registerSystemPromptComposeHook({
        id: "worldbook_always_active",
        function: systemPromptHook
    });
    ToolPkg.registerPromptFinalizeHook({
        id: "worldbook_keyword_inject",
        function: finalizeHook
    });
    return true;
}
