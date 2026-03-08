const verifyTitle = document.querySelector("#verify-title");
const verifyCopy = document.querySelector("#verify-copy");
const verifyStatus = document.querySelector("#verify-status");

bootstrapVerification();

async function bootstrapVerification() {
  const token = new URLSearchParams(window.location.search).get("token")?.trim() || "";
  if (!token) {
    renderVerificationState(
      "Confirmation link missing",
      "Open the latest confirmation email and use the full link again.",
      "The verification token was not found in this URL."
    );
    return;
  }

  try {
    const response = await fetch(`/api/auth/verify?token=${encodeURIComponent(token)}`);
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to confirm this email right now.");
    }

    renderVerificationState(
      "Email confirmed",
      "Your account is verified now. Spectrum Code Forge and premium upgrade are unlocked.",
      "Confirmation complete. You can continue straight to the Spectrum Code Forge workspace."
    );
  } catch (error) {
    renderVerificationState(
      "Confirmation failed",
      "This link may be expired or already used. Sign in and request a new confirmation email.",
      error.message || "Unable to confirm this email right now."
    );
  }
}

function renderVerificationState(title, copy, status) {
  if (verifyTitle) {
    verifyTitle.textContent = title;
  }

  if (verifyCopy) {
    verifyCopy.textContent = copy;
  }

  if (verifyStatus) {
    verifyStatus.textContent = status;
  }
}
