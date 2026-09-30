# Política de privacidade — File Browser para Android

Esta app é um cliente para servidores [File Browser](https://github.com/filebrowser/filebrowser)
geridos pelo próprio utilizador. O programador da app não recolhe, não recebe e não tem
acesso a quaisquer dados.

**Dados guardados no dispositivo**
- Endereço do servidor e nome de utilizador.
- Palavra-passe, apenas se o utilizador activar "Manter sessão iniciada". Fica cifrada
  com uma chave do Android Keystore que não sai do dispositivo. É apagada ao terminar
  sessão.
- Fila de uploads e downloads em curso.

**Dados enviados**
- As credenciais e os ficheiros são enviados exclusivamente para o servidor indicado
  pelo utilizador, sempre por HTTPS.
- A app não usa serviços de análise, publicidade nem relatórios de erros de terceiros.

**Permissões**
- Internet: comunicar com o servidor do utilizador.
- Notificações: mostrar o progresso das transferências.
- Serviço em primeiro plano (sincronização de dados): continuar transferências com a
  app em segundo plano.
- Os ficheiros do telemóvel só são acedidos quando o utilizador os escolhe no selector
  do sistema.