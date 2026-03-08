const resetTitle = document.querySelector("#reset-title");
const resetCopy = document.querySelector("#reset-copy");
const resetStatus = document.querySelector("#reset-status");
const resetForm = document.querySelector("#reset-form");
const resetPassword = document.querySelector("#reset-password");
const resetConfirmPassword = document.querySelector("#reset-confirm-password");
const resetSubmit = document.querySelector("#reset-submit");
const resetToggleButtons = document.querySelectorAll("[data-reset-toggle]");

const resetToken = new URLSearchParams(window.location.search).get("token")?.trim() || "";

bootstrapReset();

function bootstrapReset() {
  for (const button of resetToggleButtons) {
    button.addEventListener("click", () => toggleResetPassword(button));
  }

  if (!resetToken) {
    renderResetState(
      "Reset link missing",
      "Open the latest password reset email and use the full link again.",
      "The reset token was not found in this URL.",
      false
    );
    return;
  }

  if (resetForm) {
    resetForm.hidden = false;
    resetForm.addEventListener("submit", handleResetSubmit);
  }

  renderResetState(
    "Create a new password",
    "Choose a strong password with at least 8 characters. You will be signed in after the update.",
    "Your reset link is ready.",
    true
  );
}

async function handleResetSubmit(event) {
  event.preventDefault();
  const password = resetPassword?.value || "";
  const confirmPassword = resetConfirmPassword?.value || "";

  if (password.length < 8) {
    renderStatus("Password must be at least 8 characters.");
    return;
  }

  if (password !== confirmPassword) {
    renderStatus("Passwords do not match.");
    return;
  }

  if (resetSubmit) {
    resetSubmit.disabled = true;
  }

  try {
    const response = await fetch("/api/auth/reset-password", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        token: resetToken,
        password
      })
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to reset the password right now.");
    }

    renderResetState(
      "Password updated",
      result.user?.emailVerified
        ? "Your password is updated and you are signed in now. Open Spectrum Code Forge and continue."
        : "Your password is updated and you are signed in now. Verify your email if this account still needs confirmation.",
      "Password reset complete.",
      false
    );
    if (resetForm) {
      resetForm.hidden = true;
    }
  } catch (error) {
    renderStatus(error.message || "Unable to reset the password right now.");
  } finally {
    if (resetSubmit) {
      resetSubmit.disabled = false;
    }
  }
}

function toggleResetPassword(button) {
  const inputId = button.dataset.resetToggle;
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

function renderResetState(title, copy, status, showForm) {
  if (resetTitle) {
    resetTitle.textContent = title;
  }
  if (resetCopy) {
    resetCopy.textContent = copy;
  }
  renderStatus(status);
  if (resetForm) {
    resetForm.hidden = !showForm;
  }
}

function renderStatus(message) {
  if (resetStatus) {
    resetStatus.textContent = message;
  }
}
