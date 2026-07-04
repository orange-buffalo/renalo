import {
  ApiError,
  apiRequest,
  getAuthToken as readAuthToken,
  setAuthToken,
} from "@/api/client";

export {
  ApiError,
  clearAuthToken,
  getAuthToken,
  redirectToLoginForExpiredSession,
} from "@/api/client";

export type UserType = "USER" | "ADMIN";

export type Profile = {
  username: string;
  type: UserType;
  passwordSignInDisabled: boolean;
  issueRefreshTokenOnPasskeyLogin: boolean;
};

export type Passkey = {
  id: number;
  device: string;
  createdAt: string;
  lastUsedAt?: string | null;
};

export type SignInLink = {
  link: string;
  expiresAt: string;
};

export async function createAuthToken(
  username: string,
  password: string,
  rememberMe: boolean,
) {
  const rememberMeDevice = rememberMe ? getCurrentDeviceLabel() : undefined;
  const response = await fetch("/api/create-auth-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ username, password, rememberMe, rememberMeDevice }),
  });

  if (!response.ok) {
    throw new ApiError(
      "Invalid username or password",
      response.status,
      await readErrorCode(response),
    );
  }

  const body = (await response.json()) as { token: string };
  setAuthToken(body.token);
  return body.token;
}

export async function refreshAccessToken() {
  const token = readAuthToken();
  const response = await fetch("/api/refresh-access-token", {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: "same-origin",
  });

  if (!response.ok) {
    throw new ApiError("Access token refresh failed", response.status);
  }

  const body = (await response.json()) as { token: string | null };
  if (body.token) {
    setAuthToken(body.token);
  }
  return body.token;
}

export function getAuthTokenExpirationTime(token: string) {
  const [, payload] = token.split(".");
  if (!payload) {
    return undefined;
  }

  try {
    const normalizedPayload = payload.replace(/-/g, "+").replace(/_/g, "/");
    const decodedPayload = JSON.parse(window.atob(normalizedPayload)) as {
      exp?: number;
    };
    return typeof decodedPayload.exp === "number"
      ? decodedPayload.exp * 1000
      : undefined;
  } catch {
    return undefined;
  }
}

export async function fetchProfile() {
  return apiRequest<Profile>("/api/profile");
}

export async function changePassword(
  currentPassword: string,
  newPassword: string,
) {
  return apiRequest<void>("/api/profile/password", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export async function disablePasswordSignIn() {
  return apiRequest<Profile>("/api/profile/disable-password-sign-in", {
    method: "POST",
  });
}

export async function enablePasswordSignIn() {
  return apiRequest<Profile>("/api/profile/enable-password-sign-in", {
    method: "POST",
  });
}

export async function disablePasskeyRefreshToken() {
  return apiRequest<Profile>("/api/profile/disable-passkey-refresh-token", {
    method: "POST",
  });
}

export async function enablePasskeyRefreshToken() {
  return apiRequest<Profile>("/api/profile/enable-passkey-refresh-token", {
    method: "POST",
  });
}

export async function createSignInLink() {
  return apiRequest<SignInLink>("/api/profile/sign-in-link", {
    method: "POST",
  });
}

export async function createAuthTokenWithSignInLink(token: string) {
  const response = await fetch("/api/create-auth-token-with-sign-in-link", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token }),
  });
  if (!response.ok) {
    throw new ApiError("Sign in link is invalid", response.status);
  }

  const body = (await response.json()) as { token: string };
  setAuthToken(body.token);
  return body.token;
}

export async function fetchPasskeys() {
  return apiRequest<Passkey[]>("/api/profile/passkeys");
}

export async function registerPasskey() {
  ensureWebAuthnSupported();
  const optionsResponse = await apiRequest<PasskeyOptionsResponse>(
    "/api/profile/passkeys/registration-options",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ device: getCurrentDeviceLabel() }),
    },
  );
  const credential = await window.navigator.credentials.create({
    publicKey: prepareCreationOptions(optionsResponse.publicKey),
  });
  if (!credential) {
    throw new Error("Passkey registration was cancelled.");
  }

  return apiRequest<Passkey>("/api/profile/passkeys", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId: optionsResponse.requestId,
      credential: registrationCredentialToJson(
        credential as PublicKeyCredential,
      ),
    }),
  });
}

export async function deletePasskey(passkeyId: number) {
  return apiRequest<void>(`/api/profile/passkeys/${passkeyId}`, {
    method: "DELETE",
  });
}

export async function createAuthTokenWithPasskey() {
  ensureWebAuthnSupported();
  const optionsResponse = await apiRequest<PasskeyOptionsResponse>(
    "/api/passkeys/authentication-options",
    { method: "POST" },
  );
  const credential = await window.navigator.credentials.get({
    publicKey: prepareRequestOptions(optionsResponse.publicKey),
  });
  if (!credential) {
    throw new Error("Passkey sign in was cancelled.");
  }

  const response = await fetch("/api/passkeys/create-auth-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestId: optionsResponse.requestId,
      credential: assertionCredentialToJson(credential as PublicKeyCredential),
    }),
  });
  if (!response.ok) {
    throw new ApiError("Passkey sign in failed", response.status);
  }
  const body = (await response.json()) as { token: string };
  setAuthToken(body.token);
  return body.token;
}

type PasskeyOptionsResponse = {
  requestId: string;
  publicKey: PublicKeyCredentialCreationOptionsJSON &
    PublicKeyCredentialRequestOptionsJSON;
};

type PublicKeyCredentialCreationOptionsJSON = Omit<
  PublicKeyCredentialCreationOptions,
  "challenge" | "user" | "excludeCredentials"
> & {
  challenge: string;
  user: Omit<PublicKeyCredentialUserEntity, "id"> & { id: string };
  excludeCredentials?: PublicKeyCredentialDescriptorJSON[];
};

type PublicKeyCredentialRequestOptionsJSON = Omit<
  PublicKeyCredentialRequestOptions,
  "challenge" | "allowCredentials"
> & {
  challenge: string;
  allowCredentials?: PublicKeyCredentialDescriptorJSON[];
};

type PublicKeyCredentialDescriptorJSON = Omit<
  PublicKeyCredentialDescriptor,
  "id"
> & { id: string };

function prepareCreationOptions(
  publicKey: PublicKeyCredentialCreationOptionsJSON,
): PublicKeyCredentialCreationOptions {
  return {
    ...publicKey,
    challenge: base64UrlToArrayBuffer(publicKey.challenge),
    user: {
      ...publicKey.user,
      id: base64UrlToArrayBuffer(publicKey.user.id),
    },
    excludeCredentials: publicKey.excludeCredentials?.map(
      prepareCredentialDescriptor,
    ),
  };
}

function prepareRequestOptions(
  publicKey: PublicKeyCredentialRequestOptionsJSON,
): PublicKeyCredentialRequestOptions {
  return {
    ...publicKey,
    challenge: base64UrlToArrayBuffer(publicKey.challenge),
    allowCredentials: publicKey.allowCredentials?.map(
      prepareCredentialDescriptor,
    ),
  };
}

function prepareCredentialDescriptor(
  descriptor: PublicKeyCredentialDescriptorJSON,
): PublicKeyCredentialDescriptor {
  return {
    ...descriptor,
    id: base64UrlToArrayBuffer(descriptor.id),
  };
}

function registrationCredentialToJson(credential: PublicKeyCredential) {
  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    id: credential.id,
    rawId: arrayBufferToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment,
    response: {
      attestationObject: arrayBufferToBase64Url(response.attestationObject),
      clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON),
      transports: response.getTransports?.() ?? [],
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}

function assertionCredentialToJson(credential: PublicKeyCredential) {
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    id: credential.id,
    rawId: arrayBufferToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment,
    response: {
      authenticatorData: arrayBufferToBase64Url(response.authenticatorData),
      clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON),
      signature: arrayBufferToBase64Url(response.signature),
      userHandle: response.userHandle
        ? arrayBufferToBase64Url(response.userHandle)
        : null,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}

function base64UrlToArrayBuffer(value: string) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(
    normalized.length + ((4 - (normalized.length % 4)) % 4),
    "=",
  );
  const binary = window.atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}

function arrayBufferToBase64Url(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return window
    .btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function ensureWebAuthnSupported() {
  if (!window.PublicKeyCredential || !window.navigator.credentials) {
    throw new Error("Passkeys are not supported by this browser.");
  }
}

function getCurrentDeviceLabel() {
  const userAgent = window.navigator.userAgent;
  const browser = getBrowserName(userAgent);
  const os = getOperatingSystemName(userAgent);
  return `${browser} on ${os}`;
}

function getBrowserName(userAgent: string) {
  if (/Edg\//.test(userAgent)) {
    return "Edge";
  }
  if (/Firefox\//.test(userAgent)) {
    return "Firefox";
  }
  if (/Chrome\//.test(userAgent) || /CriOS\//.test(userAgent)) {
    return "Chrome";
  }
  if (/Safari\//.test(userAgent)) {
    return "Safari";
  }
  return "Browser";
}

function getOperatingSystemName(userAgent: string) {
  if (/Windows NT/.test(userAgent)) {
    return "Windows";
  }
  if (/Android/.test(userAgent)) {
    return "Android";
  }
  if (/iPhone|iPad|iPod/.test(userAgent)) {
    return "iOS";
  }
  if (/Mac OS X/.test(userAgent)) {
    return "macOS";
  }
  if (/Linux/.test(userAgent)) {
    return "Linux";
  }
  return "this device";
}

async function readErrorCode(response: Response) {
  try {
    const body = (await response.json()) as { code?: string };
    return body.code;
  } catch {
    return undefined;
  }
}
