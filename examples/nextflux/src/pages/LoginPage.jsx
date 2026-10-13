import appIcon from "@/assets/app-icon.svg";
import {useEffect, useState} from "react";
import {useNavigate} from "react-router";
import {authState, login} from "@/stores/authStore";
import {
    Button,
    FieldError,
    Form,
    Input,
    InputGroup,
    Label,
    Link,
    Spinner,
    TextField,
} from "@heroui/react";
import {
  Eye,
  EyeClosed,
} from "lucide-react";
import {
  WarningIcon as CircleAlert,
} from "@/components/ui/icons.jsx";
import {useStore} from "@nanostores/react";
import {useTranslation} from "react-i18next";
import {Segmented} from "@/components/ui/settingItem.jsx";
import {claimStagger} from "@/design/motion.js";
import "@/components/Settings/settings.css";
import { SERVER_URL } from "@/toolbox/server.mjs";

export default function LoginPage() {
    const navigate = useNavigate();
    const $auth = useStore(authState);
    const {t} = useTranslation();
    const [authType, setAuthType] = useState("token");
    const [serverUrl, setServerUrl] = useState(SERVER_URL);
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [token, setToken] = useState("");
    const [isVisible, setIsVisible] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [stagger] = useState(() => claimStagger("login"));
    const isAuthenticated = Boolean($auth.serverUrl && ($auth.token || ($auth.username && $auth.password)));

    useEffect(() => {
        if ($auth.serverUrl && ($auth.token || ($auth.username && $auth.password))) {
            navigate("/");
        }
    }, [$auth.serverUrl, $auth.username, $auth.password, $auth.token, navigate]);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError("");

        try {
            await login(
                serverUrl,
                authType === "basic" ? username : "",
                authType === "basic" ? password : "",
                authType === "token" ? token : "",
            );
            navigate("/");
        } catch (err) {
            setError(err?.message || t("auth.loginFailed"));
        } finally {
            setLoading(false);
        }
    };

    const switchAuthType = (next) => {
        if (next === authType) return;
        setAuthType(next);
        setError("");
        if (next === "basic") {
            setUsername("");
            setPassword("");
        }
        setToken("");
    };

    // Already signed in: render nothing for the frame before the redirect runs,
    // instead of flashing the form.
    if (isAuthenticated) return null;

    return (
        <main className="nf-auth">
            <div className={stagger ? "nf-auth-column ds-stagger" : "nf-auth-column"}>
                <header className="nf-auth-hero">
                    <img src={appIcon} alt="" className="nf-app-icon nf-auth-icon" aria-hidden="true"/>
                    <h1 className="ds-large-title">NextFlux</h1>
                    <p className="ds-subhead">连接你的 Miniflux，继续阅读。</p>
                </header>

                <div className="ds-group-card nf-auth-card">
                    <Segmented
                        block
                        ariaLabel={t("auth.login")}
                        selectedKey={authType}
                        onSelectionChange={switchAuthType}
                        options={[
                            {value: "token", label: t("auth.tokenAuth")},
                            {value: "basic", label: t("auth.basicAuth")},
                        ]}
                    />
                    <Form
                        className="nf-auth-form"
                        validationBehavior="native"
                        onSubmit={handleSubmit}
                        aria-busy={loading}
                    >
                        <TextField isRequired name="serverUrl" className="nf-field">
                            <Label>{t("auth.serverUrl")}</Label>
                            <Input
                                placeholder={t("auth.serverUrlPlaceholder")}
                                type="url"
                                readOnly
                                value={serverUrl}
                                onChange={(e) => setServerUrl(e.target.value)}
                                className="w-full"
                            />
                            <FieldError/>
                        </TextField>

                        {authType === "basic" ? (
                            <>
                                <TextField isRequired name="username" className="nf-field">
                                    <Label>{t("auth.username")}</Label>
                                    <Input
                                        placeholder={t("auth.usernamePlaceholder")}
                                        type="text"
                                        autoComplete="username"
                                        value={username}
                                        onChange={(e) => { setUsername(e.target.value); setError(""); }}
                                        className="w-full"
                                    />
                                    <FieldError/>
                                </TextField>

                                <TextField isRequired name="password" className="nf-field">
                                    <Label>{t("auth.password")}</Label>
                                    <InputGroup>
                                        <InputGroup.Input
                                            placeholder={t("auth.passwordPlaceholder")}
                                            type={isVisible ? "text" : "password"}
                                            autoComplete="current-password"
                                            value={password}
                                            onChange={(e) => { setPassword(e.target.value); setError(""); }}
                                            className="w-full"
                                        />
                                        <InputGroup.Suffix>
                                            <button
                                                className="nf-auth-reveal"
                                                type="button"
                                                onClick={() => setIsVisible(!isVisible)}
                                                aria-label="Toggle password visibility"
                                                aria-pressed={isVisible}
                                            >
                                                {isVisible ? (
                                                    <EyeClosed className="pointer-events-none shrink-0"/>
                                                ) : (
                                                    <Eye className="pointer-events-none shrink-0"/>
                                                )}
                                            </button>
                                        </InputGroup.Suffix>
                                    </InputGroup>
                                    <FieldError/>
                                </TextField>
                            </>
                        ) : (
                            <TextField isRequired name="token" className="nf-field">
                                <Label>{t("auth.token")}</Label>
                                <Input
                                    placeholder={t("auth.tokenPlaceholder")}
                                    type="password"
                                    autoComplete="off"
                                    value={token}
                                    onChange={(e) => { setToken(e.target.value); setError(""); }}
                                    className="w-full"
                                />
                                <FieldError/>
                            </TextField>
                        )}

                        {error && (
                            <div role="alert" className="ds-banner ds-tone-danger nf-auth-banner">
                                <CircleAlert aria-hidden="true"/>
                                <span>{error}</span>
                            </div>
                        )}

                        <Button
                            fullWidth
                            size="lg"
                            type="submit"
                            isPending={loading}
                        >
                            {loading && <Spinner color="current" size="sm"/>}
                            {t("common.login")}
                        </Button>
                    </Form>
                </div>

                <p className="nf-auth-help">
                    <span>{t("auth.needMoreInfo")}</span>
                    <Link href="https://miniflux.app" size="sm">
                        {t("auth.visitMiniflux")}
                        <Link.Icon/>
                    </Link>
                </p>
            </div>
        </main>
    );
}
