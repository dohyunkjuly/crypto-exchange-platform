/** The slice of Google Identity Services this app touches. The script is loaded at runtime
 *  and has no bundled types. */
interface GoogleCredentialResponse {
  credential: string;
}
interface Window {
  google?: {
    accounts: {
      id: {
        initialize(config: {
          client_id: string;
          callback: (response: GoogleCredentialResponse) => void;
        }): void;
        renderButton(
          parent: HTMLElement,
          options: {
            theme?: "outline" | "filled_black";
            size?: "large" | "medium" | "small";
            shape?: "rectangular" | "pill" | "circle" | "square";
            text?: "signin_with" | "signup_with" | "continue_with";
            width?: number;
            logo_alignment?: "left" | "center";
            locale?: string;
          },
        ): void;
      };
    };
  };
}
