const authModal = document.querySelector("#auth-modal");
const authCloseButton = document.querySelector("#auth-close");
const authMessage = document.querySelector("#auth-message");
const authTitle = document.querySelector("#auth-title");
const authCopy = document.querySelector("#auth-copy");
const authKicker = document.querySelector("#auth-kicker");
const signInForm = document.querySelector("#signin-form");
const forgotForm = document.querySelector("#forgot-form");
const signUpForm = document.querySelector("#signup-form");
const authTabs = document.querySelectorAll("[data-auth-view]");
const authOpenButtons = document.querySelectorAll("[data-auth-open]");
const forgotPasswordButtons = document.querySelectorAll("[data-auth-forgot]");
const authBackSigninButtons = document.querySelectorAll("[data-auth-back-signin]");
const authGuestBlocks = document.querySelectorAll("[data-auth-guest]");
const authUserBlocks = document.querySelectorAll("[data-auth-user]");
const authNameFields = document.querySelectorAll("[data-auth-name]");
const logoutButtons = document.querySelectorAll("[data-auth-logout]");
const homeHistoryGuest = document.querySelector("#home-history-guest");
const homeHistoryEmpty = document.querySelector("#home-history-empty");
const homeHistoryList = document.querySelector("#home-history-list");
const workspaceEntryButtons = document.querySelectorAll("[data-workspace-entry]");
const passwordToggleButtons = document.querySelectorAll("[data-password-toggle]");
const AUTH_FLASH_KEY = "solver-auth-flash";

const authState = {
  user: null,
  view: "signin",
  intent: null,
  isSubmitting: false
};

bootstrapAuth();

function bootstrapAuth() {
  for (const button of authOpenButtons) {
    button.addEventListener("click", () => openAuthModal(button.dataset.authOpen || "signin"));
  }

  for (const tab of authTabs) {
    tab.addEventListener("click", () => setAuthView(tab.dataset.authView || "signin"));
  }

  if (authCloseButton) {
    authCloseButton.addEventListener("click", closeAuthModal);
  }

  if (authModal) {
    authModal.addEventListener("click", (event) => {
      if (event.target === authModal) {
        closeAuthModal();
      }
    });
  }

  if (signInForm) {
    signInForm.addEventListener("submit", (event) => submitAuthForm(event, "signin"));
  }

  if (forgotForm) {
    forgotForm.addEventListener("submit", submitForgotPasswordForm);
  }

  if (signUpForm) {
    signUpForm.addEventListener("submit", (event) => submitAuthForm(event, "signup"));
  }

  for (const button of forgotPasswordButtons) {
    button.addEventListener("click", () => setAuthView("forgot"));
  }

  for (const button of authBackSigninButtons) {
    button.addEventListener("click", () => setAuthView("signin"));
  }

  for (const button of logoutButtons) {
    button.addEventListener("click", handleLogout);
  }

  for (const button of workspaceEntryButtons) {
    button.addEventListener("click", handleWorkspaceEntry);
  }

  for (const button of passwordToggleButtons) {
    button.addEventListener("click", () => togglePasswordVisibility(button));
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && authModal && !authModal.hidden) {
      closeAuthModal();
    }
  });

  setAuthView("signin");
  refreshSession();
}

async function submitAuthForm(event, mode) {
  event.preventDefault();
  if (authState.isSubmitting) {
    return;
  }

  clearAuthMessage();

  const form = mode === "signup" ? signUpForm : signInForm;
  if (!form) {
    return;
  }

  const formData = new FormData(form);
  const payload =
    mode === "signup"
      ? {
          name: String(formData.get("name") || "").trim(),
          email: String(formData.get("email") || "").trim(),
          password: String(formData.get("password") || "")
        }
      : {
          email: String(formData.get("email") || "").trim(),
          password: String(formData.get("password") || "")
        };

  if (!payload.email) {
    showAuthMessage("Enter your email first.");
    return;
  }

  if (!payload.password || payload.password.length < 8) {
    showAuthMessage("Password must be at least 8 characters.");
    return;
  }

  if (mode === "signup" && !payload.name) {
    showAuthMessage("Enter your name first.");
    return;
  }

  setAuthSubmitting(true);

  try {
    const response = await fetch(mode === "signup" ? "/api/auth/signup" : "/api/auth/login", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to continue.");
    }

    form.reset();
    resetPasswordToggles();
    setSession(result.user || null);

    const verificationPending = Boolean(result.user) && !result.user.emailVerified;
    const verificationMessage =
      result.verificationEmailSent === true
        ? "Check your inbox and confirm your email before using Spectrum Code Forge."
        : result.verificationNotice || "Verification is still pending. Open Spectrum Code Forge and resend the confirmation email.";

    if (authState.intent === "workspace") {
      writeAuthFlash(verificationPending ? verificationMessage : "Spectrum Code Forge is unlocked. You can generate and save solutions now.");
      window.location.href = "/workspace";
      return;
    }

    if (verificationPending) {
      showAuthMessage(verificationMessage);
      return;
    }

    closeAuthModal();
  } catch (error) {
    showAuthMessage(error.message || "Unable to continue.");
  } finally {
    setAuthSubmitting(false);
  }
}

async function submitForgotPasswordForm(event) {
  event.preventDefault();
  if (authState.isSubmitting || !forgotForm) {
    return;
  }

  clearAuthMessage();
  const formData = new FormData(forgotForm);
  const email = String(formData.get("email") || "").trim();
  if (!email) {
    showAuthMessage("Enter your email first.");
    return;
  }

  setAuthSubmitting(true);

  try {
    const response = await fetch("/api/auth/forgot-password", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ email })
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to send the reset link right now.");
    }

    forgotForm.reset();
    if (signInForm) {
      const signInEmail = signInForm.querySelector('input[name="email"]');
      if (signInEmail instanceof HTMLInputElement) {
        signInEmail.value = email;
      }
    }
    setAuthView("signin");
    showAuthMessage(result.message || "If that email exists, reset instructions are on the way.");
  } catch (error) {
    showAuthMessage(error.message || "Unable to send the reset link right now.");
  } finally {
    setAuthSubmitting(false);
  }
}

async function handleLogout() {
  clearAuthMessage();
  const onWorkspacePage = window.location.pathname === "/workspace" || window.location.pathname === "/builder.html";

  try {
    const response = await fetch("/api/auth/logout", {
      method: "POST"
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to sign out.");
    }

    setSession(result.user || null);
    if (onWorkspacePage) {
      window.location.href = "/";
    }
  } catch (error) {
    showAuthMessage(error.message || "Unable to sign out.");
  }
}

async function refreshSession() {
  try {
    const response = await fetch("/api/auth/session");
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to load session.");
    }
    setSession(result.authenticated ? result.user : null);
  } catch (error) {
    setSession(null);
  }
}

function setSession(user) {
  authState.user = user;
  updateAuthUi();
  updateWorkspaceEntry();
  void refreshHomeHistory();
  window.dispatchEvent(
    new CustomEvent("solver-auth-changed", {
      detail: {
        user
      }
    })
  );
}

function updateAuthUi() {
  const authenticated = Boolean(authState.user);

  for (const block of authGuestBlocks) {
    block.hidden = authenticated;
  }

  for (const block of authUserBlocks) {
    block.hidden = !authenticated;
  }

  for (const field of authNameFields) {
    field.textContent = authenticated ? authState.user.name || authState.user.email || "Account" : "";
  }
}

function openAuthModal(view, intent = null) {
  if (!authModal) {
    return;
  }

  authState.intent = intent;
  setAuthView(view);
  clearAuthMessage();
  authModal.hidden = false;
}

function closeAuthModal() {
  if (!authModal) {
    return;
  }

  authModal.hidden = true;
  authState.intent = null;
  clearAuthMessage();
}

function setAuthView(view) {
  if (view === "signup") {
    authState.view = "signup";
  } else if (view === "forgot") {
    authState.view = "forgot";
  } else {
    authState.view = "signin";
  }

  for (const tab of authTabs) {
    tab.classList.toggle("is-active", tab.dataset.authView === authState.view);
  }

  if (signInForm) {
    signInForm.hidden = authState.view !== "signin";
  }

  if (forgotForm) {
    forgotForm.hidden = authState.view !== "forgot";
  }

  if (signUpForm) {
    signUpForm.hidden = authState.view !== "signup";
  }

  if (authTitle && authCopy && authKicker) {
    if (authState.view === "signup") {
      authKicker.textContent = "New account";
      authTitle.textContent = "Create your study account";
      authCopy.textContent = "Save coding chats, pin strong answers, and come back to them anytime.";
    } else if (authState.view === "forgot") {
      authKicker.textContent = "Reset access";
      authTitle.textContent = "Recover your password";
      authCopy.textContent = "Enter the email linked to your account and we will send a secure reset link.";
    } else {
      authKicker.textContent = "Welcome back";
      authTitle.textContent = "Sign in and continue";
      authCopy.textContent = "Open Spectrum Code Forge, unlock code output, and continue from saved history.";
    }
  }
}

function setAuthSubmitting(isSubmitting) {
  authState.isSubmitting = isSubmitting;
  const submitButtons = document.querySelectorAll(".auth-submit");

  for (const button of submitButtons) {
    button.disabled = isSubmitting;
  }
}

function showAuthMessage(message) {
  if (!authMessage) {
    return;
  }

  authMessage.hidden = false;
  authMessage.textContent = message;
}

function clearAuthMessage() {
  if (!authMessage) {
    return;
  }

  authMessage.hidden = true;
  authMessage.textContent = "";
}

function handleWorkspaceEntry() {
  if (authState.user) {
    window.location.href = "/workspace";
    return;
  }

  openAuthModal("signin", "workspace");
  showAuthMessage("Sign in first to open Spectrum Code Forge and unlock code output.");
}

function updateWorkspaceEntry() {
  for (const button of workspaceEntryButtons) {
    if (button.classList.contains("home-cta")) {
      if (authState.user) {
        if (!authState.user.emailVerified) {
          button.innerHTML = `
            <span class="home-cta-kicker">Verification</span>
            <strong>Verify email first</strong>
            <span class="home-cta-copy">Open the workspace and resend your confirmation email if needed.</span>
          `;
          continue;
        }

        button.innerHTML = `
          <span class="home-cta-kicker">Workspace</span>
          <strong>Go to workspace</strong>
          <span class="home-cta-copy">Continue solving and reopen your saved chats.</span>
        `;
      } else {
        button.innerHTML = `
          <span class="home-cta-kicker">Workspace</span>
          <strong>Sign in to enter</strong>
          <span class="home-cta-copy">Code output unlocks only after sign in.</span>
        `;
      }
      continue;
    }

    if (authState.user && !authState.user.emailVerified) {
      button.textContent = "Verify to solve";
      continue;
    }

    button.textContent = authState.user ? "Go to workspace" : "Open Spectrum Code Forge";
  }
}

function togglePasswordVisibility(button) {
  const inputId = button.dataset.passwordToggle;
  if (!inputId) {
    return;
  }

  const input = document.getElementById(inputId);
  if (!(input instanceof HTMLInputElement)) {
    return;
  }

  const nextType = input.type === "password" ? "text" : "password";
  input.type = nextType;
  button.textContent = nextType === "password" ? "Show" : "Hide";
}

function resetPasswordToggles() {
  for (const button of passwordToggleButtons) {
    const inputId = button.dataset.passwordToggle;
    const input = inputId ? document.getElementById(inputId) : null;
    if (input instanceof HTMLInputElement) {
      input.type = "password";
    }
    button.textContent = "Show";
  }
}

async function refreshHomeHistory() {
  if (!homeHistoryGuest || !homeHistoryEmpty || !homeHistoryList) {
    return;
  }

  if (!authState.user) {
    homeHistoryGuest.hidden = false;
    homeHistoryEmpty.hidden = true;
    homeHistoryList.hidden = true;
    homeHistoryList.innerHTML = "";
    return;
  }

  try {
    const response = await fetch("/api/history");
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to load saved chats.");
    }

    const items = Array.isArray(result.items) ? result.items.slice(0, 3) : [];
    homeHistoryGuest.hidden = true;

    if (!items.length) {
      homeHistoryEmpty.hidden = false;
      homeHistoryList.hidden = true;
      homeHistoryList.innerHTML = "";
      return;
    }

    homeHistoryEmpty.hidden = true;
    homeHistoryList.hidden = false;
    homeHistoryList.innerHTML = items
      .map(
        (item) => `
          <article class="home-history-chip ${item.pinned ? "is-pinned" : ""}">
            <strong>${escapeHtml(item.title || "Saved coding chat")}</strong>
            <span>${escapeHtml(item.language || "Code")} • ${escapeHtml(formatTimestamp(item.createdAt))}${item.pinned ? " • pinned" : ""}</span>
          </article>
        `
      )
      .join("");
  } catch (error) {
    homeHistoryGuest.hidden = true;
    homeHistoryEmpty.hidden = false;
    homeHistoryEmpty.textContent = error.message || "Unable to load saved chats.";
    homeHistoryList.hidden = true;
    homeHistoryList.innerHTML = "";
  }
}

async function resendVerificationEmail() {
  const response = await fetch("/api/auth/resend-verification", {
    method: "POST"
  });
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || "Unable to resend the verification email.");
  }

  if (result.user) {
    setSession(result.user);
  }

  return result;
}

function writeAuthFlash(message) {
  if (!message) {
    return;
  }

  try {
    sessionStorage.setItem(AUTH_FLASH_KEY, message);
  } catch (error) {
    // Ignore storage failures and fall back to in-page messages.
  }
}

function consumeAuthFlash() {
  try {
    const message = sessionStorage.getItem(AUTH_FLASH_KEY);
    if (message) {
      sessionStorage.removeItem(AUTH_FLASH_KEY);
    }
    return message || "";
  } catch (error) {
    return "";
  }
}

function formatTimestamp(value) {
  if (!value) {
    return "saved chat";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "saved chat";
  }

  return date.toLocaleDateString([], {
    month: "short",
    day: "numeric"
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

window.solverAuth = {
  getUser() {
    return authState.user;
  },
  refreshSession,
  resendVerification: resendVerificationEmail,
  consumeFlash: consumeAuthFlash,
  open(view = "signin", intent = null) {
    openAuthModal(view, intent);
  }
};
