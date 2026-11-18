using System.Text.Json;
using SkillTreeEditor.Desktop;
using Xunit;

namespace SkillTreeEditor.Server.Tests;

public sealed class AndroidPrivateNetworkTests
{
    private const string Authority = "editor.sample.ts.net:8443";
    private const string EditorRoute = """
        {"TCP":{"8443":{"HTTPS":true}},"Web":{"editor.sample.ts.net:8443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:5285"}}}}}
        """;

    [Fact]
    public void ReusesOnlyTheDedicatedPrivateRoute()
    {
        Assert.True(HasRoute(EditorRoute));
        Assert.False(HasRoute("{}"));
        Assert.False(HasRoute("""{"TCP":{"443":{"HTTPS":true}}}"""));
        Assert.Throws<InvalidOperationException>(() => HasRoute(EditorRoute.Replace("5285", "5274")));
        Assert.Throws<InvalidOperationException>(() => HasRoute(EditorRoute.Replace("HTTPS", "TCPForward")));
        Assert.Throws<InvalidOperationException>(() => HasRoute("{\"Foreground\":{\"other\":" + EditorRoute + "}}"));
    }

    [Fact]
    public void RefusesFunnelEvenWhenTheProxyIsCorrect()
    {
        var config = EditorRoute.Insert(1, "\"AllowFunnel\":{\"editor.sample.ts.net:8443\":true},");
        Assert.Throws<InvalidOperationException>(() => HasRoute(config));
    }

    private static bool HasRoute(string json)
    {
        using var config = JsonDocument.Parse(json);
        return AndroidPrivateNetwork.HasPrivateRoute(config.RootElement, Authority);
    }
}
